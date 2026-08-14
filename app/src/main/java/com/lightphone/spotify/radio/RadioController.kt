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
    /** True from a manual "check now" until the poll it forced has answered. */
    val metadataRefreshing: Boolean = false,
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
    private val recognizer = com.lightphone.spotify.radio.recognize.SongRecognizer(context)

    /** Set by [refreshNowPlaying] so the user's press skips the recognition throttle too. */
    private var forceRecognize = false
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

    /**
     * Hooks into the speaker bridge, set by `AppViewModel` when one is configured.
     *
     * While [_externalOnly] is set the audio is OwnTone's, not this player's — so pause, resume
     * and stop have to be *sent somewhere* rather than applied to a `MediaPlayer` that was never
     * opened. Before these existed, pause flipped the UI and changed nothing, resume was a hard
     * no-op, and stop left the HomePods playing a station the phone claimed was gone.
     */
    var onExternalPause: (() -> Unit)? = null
    var onExternalResume: (() -> Unit)? = null
    var onExternalStop: (() -> Unit)? = null

    /** Set the UI state to show this station as playing without starting local audio.
     * Used when audio is routed through an external bridge (OwnTone/AirPlay). */
    fun pretendPlaying(stream: RadioStation) {
        // The bridge is a second player exactly like the local one: Spotify must be paused
        // before it starts, or the phone keeps playing music under the HomePods' radio.
        onStartRadio()
        releasePlayer()
        _externalOnly = true
        needsReopen = false
        _state.value = RadioUiState(
            stream = stream,
            buffering = false,
            artworkUrl = stream.artworkUrl,
            isPlaying = true,
        )
        startMetadata(stream)
    }

    private var _externalOnly = false

    fun play(stream: RadioStation) {
        _externalOnly = false
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
        if (_externalOnly) {
            onExternalPause?.invoke()
            _state.value = _state.value.copy(isPlaying = false)
            return
        }
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
        if (_externalOnly) {
            onExternalResume?.invoke()
            _state.value = _state.value.copy(isPlaying = true)
            return
        }
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
        if (_externalOnly) {
            // Leaving radio must silence the speakers too, not just this screen.
            onExternalStop?.invoke()
            _externalOnly = false
        }
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
     * Check what is on *right now*, because the user asked. Drops the Spinitron playlist pin so
     * WNYU re-resolves from the station page, then restarts the poll loop — whose first
     * iteration runs immediately. Works for every metadata source; a station with none has
     * nothing to check and the button's press must not leave a spinner running forever.
     */
    fun refreshNowPlaying() {
        val stream = _state.value.stream ?: return
        val special = StationMetadata.sourceFor(stream.title, stream.url)
        if (stream.metadata is RadioStation.MetadataSource.None &&
            special == StationMetadata.Source.NONE &&
            !recognizer.available()
        ) {
            return
        }
        stationMetadata.invalidate()
        forceRecognize = true
        _state.value = _state.value.copy(metadataRefreshing = true)
        startMetadata(stream)
    }

    /**
     * Poll NTS for what is on. Live shows change on the hour and mixtape tracks every few minutes, so
     * [METADATA_INTERVAL_MS] is generous — this is a label, not a progress bar.
     */
    private fun startMetadata(stream: RadioStation) {
        metadataJob?.cancel()
        val special = StationMetadata.sourceFor(stream.title, stream.url)
        // A station with no metadata source at all still gets the loop when the recogniser can
        // hear it — that is the only way such a station will ever have a label.
        if (stream.metadata is RadioStation.MetadataSource.None &&
            special == StationMetadata.Source.NONE &&
            !recognizer.available()
        ) {
            return
        }
        metadataJob = scope.launch {
            // What the *station's own* source last said and when it last said something new —
            // the freshness that decides whether the recogniser gets to speak at all.
            var lastPrimaryTitle: String? = null
            var lastPrimaryChangeAtMs = System.currentTimeMillis()
            var lastRecognizeAtMs = 0L
            var recognizedApplied = false
            var boundaryHint = false
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
                val nowMs = System.currentTimeMillis()
                if (now?.title != null && now.title != lastPrimaryTitle) {
                    lastPrimaryTitle = now.title
                    lastPrimaryChangeAtMs = nowMs
                    // Fresh words from the station's own source outrank any recognition: they
                    // are exact where a fingerprint is a good guess.
                    recognizedApplied = false
                }
                // Apply the primary reading — unless a recognition is standing in for a source
                // that has gone quiet, in which case re-printing the stale line every poll
                // would erase the better answer.
                if (now != null && _state.value.stream?.id == stream.id && !recognizedApplied) {
                    _state.value = _state.value.copy(
                        nowPlayingTitle = now.title,
                        artworkUrl = if (special != StationMetadata.Source.NONE) {
                            // Per-spin art must track the song: a spin with no cover means no
                            // cover, not the previous song's held over.
                            now.artworkUrl ?: stream.artworkUrl
                        } else {
                            // Mixtape covers are fixed, so a null from Firestore must not blank
                            // them.
                            now.artworkUrl ?: _state.value.artworkUrl
                        },
                    )
                }

                // The recogniser speaks only when the station's own source has nothing to say:
                // no source at all, or a line that has not moved in longer than two songs.
                // WNYU with a logging DJ never gets here — spins change every ~3 minutes and
                // cost nothing — but a DJ who is not logging went 2h dark on Spinitron the
                // night this was built, and this is what fills that hole.
                val stale = now == null ||
                    nowMs - lastPrimaryChangeAtMs > RECOGNIZE_WHEN_STALE_MS
                val interval = if (boundaryHint) {
                    // The last sample faded out at its end — a song boundary. Listen again on
                    // the next poll rather than waiting out the full throttle: this is Gio's
                    // "songs are marked once there's a gap in the music".
                    RECOGNIZE_BOUNDARY_RETRY_MS
                } else {
                    RECOGNIZE_MIN_INTERVAL_MS
                }
                if (stale && recognizer.available() && _state.value.isPlaying &&
                    _state.value.stream?.id == stream.id &&
                    (forceRecognize || nowMs - lastRecognizeAtMs > interval)
                ) {
                    forceRecognize = false
                    lastRecognizeAtMs = nowMs
                    val hit = recognizer.recognize(stream.url)
                    boundaryHint = recognizer.lastTailSilent
                    if (hit != null && _state.value.stream?.id == stream.id) {
                        recognizedApplied = true
                        _state.value = _state.value.copy(
                            nowPlayingTitle = "${hit.artist} - ${hit.title}",
                            // Shazam's own cover; the song rule from the spin art applies —
                            // no cover means the station's, never the previous song's.
                            artworkUrl = hit.artUrl ?: stream.artworkUrl,
                        )
                    }
                }

                // A forced check has now been answered — even by "nothing newer", which is an
                // answer too. Cleared last so the refresh glyph also covers a recognition the
                // press forced, and cleared whether or not anything was found, or a failed
                // fetch would leave it lit until the station changed songs.
                if (_state.value.stream?.id == stream.id && _state.value.metadataRefreshing) {
                    _state.value = _state.value.copy(metadataRefreshing = false)
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

        /** How long the station's own source may sit unchanged before recognition steps in. */
        const val RECOGNIZE_WHEN_STALE_MS = 6 * 60_000L

        /** Normal spacing between recognitions — a song's length, roughly. */
        const val RECOGNIZE_MIN_INTERVAL_MS = 210_000L

        /** After a fade-out hint: shorter than the poll, so the very next loop listens again. */
        const val RECOGNIZE_BOUNDARY_RETRY_MS = 25_000L

        /** Three tries over ~6s. Beyond that the user is better served by an error than a spinner. */
        const val MAX_RECONNECTS = 3
        const val RECONNECT_DELAY_MS = 1_000L
    }
}
