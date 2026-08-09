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
import com.lightphone.spotify.playback.SleepTimer

/** What the radio tab and the shared Now Playing screen render. */
data class RadioUiState(
    val stream: RadioStation? = null,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    /** Show or track title from NTS, when it has one. */
    val nowPlayingTitle: String? = null,
    /** Live channels get the current show's art; mixtapes and directory stations a fixed one. */
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
    private val icecast = IcecastApi()
    private val stationMetadata = StationMetadataApi()
    private var player: MediaPlayer? = null
    private var metadataJob: Job? = null
    private var focusRequest: AudioFocusRequest? = null

    /**
     * Reconnect attempts made for the stream currently loaded. Reset by [play] and by a successful
     * prepare, so a stream that drops after an hour gets a fresh budget rather than the leftovers of a
     * bad start.
     */
    private var reconnects = 0

    /**
     * True once the loaded `MediaPlayer` can no longer be started.
     *
     * This is the fix for "after you lose connection it doesn't like to play again". A `MediaPlayer`
     * whose `onError` fired is in the **Error** state and one that reached `onCompletion` — which for a
     * live stream means the socket closed, not that anything ended — is in **PlaybackCompleted**. From
     * either state `start()` does nothing useful, and the old [resume] wrapped it in `runCatching`, so
     * the failure was swallowed and the play button stayed dead for the rest of the session. The only
     * way back is a brand-new player on the same URL, so [resume] reopens instead of starting.
     */
    private var needsReopen = false

    private val audioManager: AudioManager? =
        context.getSystemService(AudioManager::class.java)

    /**
     * Sleep-timer gain for this player.
     *
     * Held rather than only applied, because the stream is reopened on every drop and a fresh
     * `MediaPlayer` starts at full volume — a reconnect three seconds into a fade would otherwise
     * come back at full blast in a dark room.
     */
    private var sleepGain: Float = 1f

    private val sleepOutput = object : SleepTimer.Output {
        override fun applyGain(gain: Float) {
            sleepGain = gain.coerceIn(0f, 1f)
            runCatching { player?.setVolume(sleepGain, sleepGain) }
        }

        override fun stopPlayback() {
            // Radio is left entirely rather than paused: a live stream has no position to come back
            // to, and the point of the timer is that nothing is running in the morning.
            stop()
        }

        override fun isPlaying(): Boolean = _state.value.isPlaying
    }

    init {
        SleepTimer.registerOutput(sleepOutput)
    }

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

    fun play(stream: RadioStation) {
        sleepGain = 1f
        reconnects = 0
        needsReopen = false
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
        openStream(stream)
        startMetadata(stream)
    }

    /**
     * Open the player on [stream]. Split out of [play] because [scheduleReconnect] needs to reopen the
     * same stream without re-announcing it: focus is already held, the metadata poll is already running,
     * and resetting the UI state would blank the title the user is looking at mid-reconnect.
     */
    private fun openStream(stream: RadioStation) {
        val mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            setOnPreparedListener {
                runCatching { setVolume(sleepGain, sleepGain) }
                start()
                reconnects = 0
                needsReopen = false
                _state.value = _state.value.copy(isPlaying = true, buffering = false, error = null)
            }
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                needsReopen = true
                if (!scheduleReconnect(stream)) {
                    _state.value = _state.value.copy(
                        isPlaying = false,
                        buffering = false,
                        error = "Stream unavailable",
                    )
                }
                true
            }
            // A live stream reaching "completion" means the connection dropped.
            setOnCompletionListener {
                needsReopen = true
                if (!scheduleReconnect(stream)) {
                    _state.value = _state.value.copy(isPlaying = false, buffering = false)
                }
            }
        }
        player = mp
        runCatching {
            mp.setDataSource(stream.url)
            mp.prepareAsync()
        }.onFailure {
            Log.w(TAG, "could not open ${stream.url}", it)
            needsReopen = true
            _state.value = _state.value.copy(buffering = false, error = "Could not open the stream")
            releasePlayer()
        }
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
        _state.value = _state.value.copy(isPlaying = false)
    }

    /**
     * Start playing again after a [pause] — or reopen the stream when the player is no longer startable.
     *
     * A pause is cheap to undo. A dropped connection is not: see [needsReopen]. Reopening also resets
     * the reconnect budget, because pressing play *is* the user saying to try again, and a stream that
     * spent its three automatic attempts an hour ago should not be permanently unplayable.
     *
     * There is a third case: no player at all, which happens when [openStream] failed outright. That
     * still has a station in state, so it can be reopened the same way.
     */
    fun resume() {
        sleepGain = 1f
        runCatching { player?.setVolume(1f, 1f) }
        val stream = _state.value.stream
        if (needsReopen || player == null) {
            if (stream == null) return
            reconnects = 0
            needsReopen = false
            releasePlayer()
            // Focus may have been abandoned by a permanent loss; asking again is harmless if held.
            if (!requestFocus()) {
                _state.value = _state.value.copy(buffering = false, error = "Could not get audio focus")
                return
            }
            _state.value = _state.value.copy(buffering = true, error = null)
            openStream(stream)
            startMetadata(stream)
            return
        }
        val mp = player ?: return
        runCatching { mp.start() }
            .onSuccess { _state.value = _state.value.copy(isPlaying = true) }
            .onFailure {
                // Should not happen now, but a swallowed failure here is exactly the old bug, so the
                // player is marked dead and the next press takes the reopen path above.
                Log.w(TAG, "resume failed; will reopen next time", it)
                needsReopen = true
            }
    }

    fun toggle() {
        if (_state.value.isPlaying) pause() else resume()
    }

    /** Leaves radio entirely, which is what handing back to Spotify needs. */
    fun stop() {
        SleepTimer.onPlaybackStopped(context)
        metadataJob?.cancel()
        metadataJob = null
        needsReopen = false
        reconnects = 0
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
    private fun startMetadata(stream: RadioStation) {
        metadataJob?.cancel()
        val special = StationMetadata.sourceFor(stream.title, stream.url)
        if (stream.metadata is RadioStation.MetadataSource.None &&
            special == StationMetadata.Source.NONE
        ) {
            return
        }
        metadataJob = scope.launch {
            while (isActive) {
                // WNYU and WNYC put nothing useful in the stream, so they are looked up
                // wherever they *do* publish it — whatever metadata source the station was
                // saved with. See [StationMetadata].
                val special = StationMetadata.sourceFor(stream.title, stream.url)
                val now = when {
                    special != StationMetadata.Source.NONE -> stationMetadata.nowPlaying(special)
                    else -> when (val source = stream.metadata) {
                        is RadioStation.MetadataSource.NtsLive ->
                            api.liveNowPlaying()[source.channel]
                        is RadioStation.MetadataSource.NtsMixtape ->
                            api.mixtapeNowPlaying(source.alias)
                        is RadioStation.MetadataSource.IcecastStatus ->
                            icecast.nowPlaying(stream.url, source.mount)
                                ?.let { NtsApi.NowPlaying(title = it) }
                        RadioStation.MetadataSource.None -> null
                    }
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

    /**
     * Reopen a dropped stream, up to [MAX_RECONNECTS] times.
     *
     * NTS is excluded ([RadioStation.shouldReconnect]) because its relays are geo-load-balanced and a
     * drop there means something else is wrong — retrying would hide it. A community station is usually
     * one machine on a university network, so a drop is expected and silently recovering is the whole
     * point.
     *
     * Returns whether a retry was scheduled, so the caller knows whether to show an error. The delay
     * backs off linearly rather than exponentially: a station that is up again is up within a couple of
     * seconds, and a station that is down is not coming back inside a reconnect budget either way.
     *
     * Focus is deliberately **not** re-requested — it is still held from [play], and asking again would
     * duck whatever took it from us.
     */
    private fun scheduleReconnect(stream: RadioStation): Boolean {
        if (!stream.shouldReconnect) return false
        // A stream the user has already left, or swapped for another one, must not resurrect itself.
        if (_state.value.stream?.id != stream.id) return false
        if (reconnects >= MAX_RECONNECTS) return false
        reconnects++
        val attempt = reconnects
        Log.i(TAG, "reconnecting to ${stream.title} (attempt $attempt)")
        _state.value = _state.value.copy(isPlaying = false, buffering = true, error = null)
        scope.launch {
            delay(RECONNECT_DELAY_MS * attempt)
            if (_state.value.stream?.id != stream.id) return@launch
            releasePlayer()
            openStream(stream)
        }
        return true
    }

    private companion object {
        const val TAG = "RadioController"
        const val METADATA_INTERVAL_MS = 30_000L

        /** Three tries over ~6s. Beyond that the user is better served by an error than a spinner. */
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
