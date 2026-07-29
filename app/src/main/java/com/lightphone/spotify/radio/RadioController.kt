package com.lightphone.spotify.radio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the radio tab and the shared Now Playing screen render. */
data class RadioUiState(
    val stream: NtsStreams.Stream? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    /** Show or track title from NTS, when it has one. */
    val nowPlayingTitle: String? = null,
    /** Live channels get the current show's art; mixtapes their fixed cover. */
    val artworkUrl: String? = null,
    val error: String? = null,
) {
    val isActive: Boolean get() = stream != null
}

/**
 * NTS Radio playback, alongside the Spotify engine rather than through it.
 *
 * ### Why a second player
 * The librespot engine decodes Spotify's own format and cannot be handed an HTTP URL. NTS serves plain
 * Icecast-style MP3/AAC, not HLS, so `MediaPlayer` is enough — which keeps ExoPlayer out of the build
 * after the TIDAL strip removed it. If reconnect behaviour on a flaky train proves poor, ExoPlayer is
 * the upgrade path; nothing outside this file would change.
 *
 * ### One of them at a time
 * Two players sharing one pair of speakers is the whole risk here, so it is handled explicitly rather
 * than left to audio focus: [play] pauses Spotify through [onStartRadio] before opening the stream, and
 * `AppViewModel` stops radio before starting Spotify. Focus is also requested, so an incoming call or
 * another app ducks and stops us the normal way.
 *
 * ### Metadata
 * Live titles come from NTS's REST API and mixtape titles from Firestore ([NtsApi]), polled while
 * playing. Radio has no duration or position to report, which is why the shared player screen has to
 * treat this as a live stream rather than a track.
 */
class RadioController(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Pause Spotify. Called before a stream opens, never after. */
    private val onStartRadio: () -> Unit,
) {
    private val _state = MutableStateFlow(RadioUiState())
    val state: StateFlow<RadioUiState> = _state.asStateFlow()

    private val api = NtsApi()
    private var player: MediaPlayer? = null
    private var metadataJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            // Radio has nothing to resume to — a live stream that comes back after a phone call has
            // moved on anyway — so a permanent loss stops rather than pauses.
            AudioManager.AUDIOFOCUS_LOSS -> stop()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
            AudioManager.AUDIOFOCUS_GAIN -> resume()
            else -> Unit
        }
    }

    fun play(stream: NtsStreams.Stream) {
        onStartRadio()
        releasePlayer()
        _state.value = RadioUiState(
            stream = stream,
            buffering = true,
            artworkUrl = stream.artworkUrl,
        )
        if (!requestFocus()) {
            _state.value = _state.value.copy(buffering = false, error = "Could not get audio focus")
            return
        }
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setOnPreparedListener {
                start()
                _state.value = _state.value.copy(isPlaying = true, buffering = false)
            }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                _state.value = _state.value.copy(
                    isPlaying = false,
                    buffering = false,
                    error = "Stream unavailable",
                )
                true
            }
            // A live stream reaching "completion" means the connection dropped.
            setOnCompletionListener {
                _state.value = _state.value.copy(isPlaying = false, buffering = false)
            }
        }
        player = mp
        runCatching {
            mp.setDataSource(stream.url)
            mp.prepareAsync()
        }.onFailure {
            Log.w(TAG, "could not open ${stream.url}", it)
            _state.value = _state.value.copy(buffering = false, error = "Could not open the stream")
            releasePlayer()
        }
        startMetadata(stream)
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        _state.value = _state.value.copy(isPlaying = false)
    }

    fun resume() {
        val mp = player ?: return
        runCatching { mp.start() }
            .onSuccess { _state.value = _state.value.copy(isPlaying = true) }
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else resume()
    }

    /** Leaves radio entirely, which is what handing back to Spotify needs. */
    fun stop() {
        metadataJob?.cancel()
        metadataJob = null
        releasePlayer()
        abandonFocus()
        _state.value = RadioUiState()
    }

    private fun releasePlayer() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
    }

    /**
     * Poll NTS for what is on. Live shows change on the hour and mixtape tracks every few minutes, so
     * [METADATA_INTERVAL_MS] is generous — this is a label, not a progress bar.
     */
    private fun startMetadata(stream: NtsStreams.Stream) {
        metadataJob?.cancel()
        metadataJob = scope.launch {
            while (isActive) {
                val now = when (stream.kind) {
                    NtsStreams.Stream.Kind.LIVE ->
                        stream.liveChannel?.let { api.liveNowPlaying()[it] }
                    NtsStreams.Stream.Kind.MIXTAPE ->
                        stream.mixtapeAlias?.let { api.mixtapeNowPlaying(it) }
                }
                if (now != null && _state.value.stream?.id == stream.id) {
                    _state.value = _state.value.copy(
                        nowPlayingTitle = now.title,
                        // Mixtape covers are fixed, so a null from Firestore must not blank them.
                        artworkUrl = now.artworkUrl ?: _state.value.artworkUrl,
                    )
                }
                delay(METADATA_INTERVAL_MS)
            }
        }
    }

    private fun requestFocus(): Boolean {
        val am = audioManager ?: return true
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        return am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        val am = audioManager ?: return
        focusRequest?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusRequest = null
    }

    private companion object {
        const val TAG = "RadioController"
        const val METADATA_INTERVAL_MS = 30_000L
    }
}
