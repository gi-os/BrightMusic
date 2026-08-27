package com.lightphone.spotify.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.lightphone.spotify.BuildConfig
import com.lightphone.spotify.audio.PhonoAudioTrackSink
import com.lightphone.spotify.podcast.PlaybackSpeed
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.data.AlbumDetailResult
import com.lightphone.spotify.data.ArtistDetailResult
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.data.mapRepositoryError
import com.lightphone.spotify.data.mapWebApiError
import com.lightphone.spotify.data.native.NativeMetadataGateway
import com.lightphone.spotify.data.native.mapNativeError
import com.lightphone.spotify.data.local.DetailCacheRepository
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.data.local.LibraryRepository
import com.lightphone.spotify.data.local.LikedTrackEntity
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.data.PlaylistDetailResult
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.SpotifyPlaylistSimple
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.local.SavedAlbumEntity
import com.lightphone.spotify.data.MusicRepository
import com.lightphone.spotify.data.SpotifyRepository
import com.lightphone.spotify.data.SearchResults
import coil.Coil
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.data.backend.BackendCapabilities
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.playback.backend.PlaybackBackend
import com.lightphone.spotify.history.PlayHistory
import com.lightphone.spotify.playback.backend.PlaybackEventListener
import com.lightphone.spotify.playback.connect.ConnectController
import com.lightphone.spotify.playback.download.OfflineDownloadCenter
import com.lightphone.spotify.playback.download.OfflinePinHygiene
import com.lightphone.spotify.playback.download.SpotifyDownloadCenter
import com.lightphone.spotify.data.webapi.SpotifyWebApi
import com.lightphone.spotify.data.webapi.WebApiAuth
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.SpotifyException
import com.lightphone.spotify.ffi.StreamingQuality
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class QueueUiItem(
    val uri: String,
    val title: String,
    val artists: String,
    val durationMs: Long,
)

data class QueueViewState(
    val nowPlaying: QueueUiItem? = null,
    val nextInQueue: List<QueueUiItem> = emptyList(),
    val contextLabel: String? = null,
    val nextFromContext: List<QueueUiItem> = emptyList(),
)

data class PlaybackUiState(
    val loggedIn: Boolean = false,
    /** False until the first cached-credential restore attempt finishes (login flow only). */
    val authInitialized: Boolean = true,
    val webApiReady: Boolean = false,
    val webApiSessionState: com.lightphone.spotify.data.webapi.WebApiSessionState =
        com.lightphone.spotify.data.webapi.WebApiSessionState.NotConfigured,
    val connected: Boolean = true,
    val networkOnline: Boolean = true,
    val reconnecting: Boolean = false,
    val sessionExpired: Boolean = false,
    val statusMessage: String? = null,
    val currentUri: String? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    /** True when playback position is stalled (buffer underrun). */
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    /** Album name, for the Now Playing info line. Null on radio and often on episodes. */
    val album: String? = null,
    val artUrl: String? = null,
    val albumId: String? = null,
    val durationMs: Long = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    /**
     * True while audio is leaving the phone through headphones or a speaker — Bluetooth or wired.
     *
     * The player's output button used to light on [ConnectUiState.isRemote] alone, which is
     * Spotify Connect and nothing else. With AirPods in, the button that says where the sound is
     * going sat dark, which is exactly backwards.
     */
    val externalOutput: Boolean = false,
    val queue: QueueViewState = QueueViewState(),
    val error: String? = null,
)

/**
 * Owns the native [LibrespotEngine], bridges its events to a [StateFlow] for the
 * UI and to the [PlaybackService]'s MediaSession, and handles Android audio
 * focus (cpal/rodio does not participate in focus, so we drive pause/resume
 * here). Process-wide singleton.
 */
class PlaybackController private constructor(
    private val appContext: Context,
    val backendChoice: BackendChoice,
    private val webApiAuth: WebApiAuth,
) : PlaybackEventListener {

    /** Set by [PlaybackService] via [attachBackend]. */
    @Volatile
    private var engineReady = false
    private lateinit var backend: PlaybackBackend

    val capabilities: BackendCapabilities = BackendCapabilities.forChoice(backendChoice)

    /**
     * What you listened to, for the journal that reads it.
     *
     * Nothing in this app uses it — the player knows what is playing and the resume store knows
     * where you were in it. It is carried because LightNotebook cannot know, and because a day's
     * listening is part of a day.
     */
    private val playHistory = PlayHistory(appContext)

    /** Offline pin façade. Spotify keeps decrypted Ogg in an oversized streaming cache. */
    val offlineDownloads: OfflineDownloadCenter =
        SpotifyDownloadCenter.also {
            it.bindEngine { runCatching { PlaybackEngineHolder.createEngine(appContext) }.getOrNull() }
        }

    private val streamingPolicy = StreamingPolicy(this)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set once by [release]; the three system callbacks must not be unregistered twice. */
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Serializes engine transport calls so play/pause/skip cannot race EndOfTrack. */
    private val transportMutex = Mutex()

    /**
     * Serializes everything that mutates login/session state at the native engine
     * level: sign-out, OAuth code exchange, cached-credential login, and the
     * spclient warm-up. Without this, a fast logout-then-login (or a background
     * warm firing mid-logout) can run `loginWithOauthCode` concurrently with
     * `rustLogout()`'s credential wipe + `session.shutdown()`, tearing credentials
     * or leaving a half-built session.
     */
    private val sessionLifecycleMutex = Mutex()

    @Volatile
    private var signingOut = false

    @Volatile
    private var appForegroundRequested = false

    /** Invoked after playback session reconnects (warm or monitor). */
    @Volatile
    var onSessionRestored: (() -> Unit)? = null

    private val webApi = SpotifyWebApi(webApiAuth)

    /**
     * Spotify Connect handoff. Lives here rather than in the ViewModel because it needs
     * both [webApi] and the ability to pause the local engine, and because a remote
     * session has to outlive any single screen.
     */
    val connect: ConnectController = ConnectController(
        webApi = webApi,
        scope = scope,
        onPauseLocal = { pauseTransport(userInitiated = true) },
        onResumeLocal = { resumeTransport() },
    )

    private val database = PhonoDatabase.get(appContext)
    val libraryRepository = LibraryRepository(
        database,
        likedTracksPageFetcher = { offset -> webApi.savedTracksPage(offset) },
        savedAlbumsPageFetcher = { offset -> webApi.savedAlbumsPage(offset) },
        playlistsPageFetcher = { offset, _ -> webApi.savedPlaylistsPage(offset) },
    )
    private val detailCache = DetailCacheRepository(
        database,
        Json { ignoreUnknownKeys = true },
    )
    private val repository: MusicRepository =
        SpotifyRepository(webApi, libraryRepository, detailCache)

    /** uri -> metadata, populated when a list is played so the now-playing bar
     *  and MediaSession have title/artist/art without any extra network call. */
    private val trackMetadata = java.util.concurrent.ConcurrentHashMap<String, TrackMetadata>()

    private val sessionCoordinator = com.lightphone.spotify.data.session.UserSessionCoordinator(
        libraryRepository = libraryRepository,
        musicRepository = repository,
        webApiAuth = webApiAuth,
        clearTrackMetadata = { trackMetadata.clear() },
        clearImageMemoryCache = { Coil.imageLoader(appContext).memoryCache?.clear() },
        rustLogout = {
            if (engineReady) {
                runCatching { requireBackend().logout() }
            }
            runCatching { webApiAuth.clearAll() }
            clearPlaybackCredentialFiles()
        },
    )

    val sessionEvents = sessionCoordinator.events

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var focusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var playWhenFocusReturns = false
    /** The latest user-initiated transport coroutine (play/next/previous/seek).
     *  A new command cancels the previous one so rapid taps coalesce to the most
     *  recent intent instead of each firing a native load / rebuild. */
    private var transportJob: Job? = null
    private var stallWatchdogJob: Job? = null
    @Volatile
    private var lastPositionMs: Long = 0

    /**
     * Target of a seek the engine has not confirmed yet, or [NO_PENDING_SEEK].
     *
     * See [settledPositionMs] for why this exists.
     */
    @Volatile
    private var pendingSeekTargetMs: Long = NO_PENDING_SEEK

    /** When [pendingSeekTargetMs] was set, for the give-up deadline. */
    @Volatile
    private var pendingSeekSinceMs: Long = 0L
    @Volatile
    private var lastPositionAtMs: Long = 0
    /** False until Rust emits Playing/PositionChanged — avoids false stall when lastPositionAtMs is 0. */
    @Volatile
    private var playbackPulseSeen: Boolean = false
    /** True once the current stall has already asked the engine to fall back to downloaded audio. */
    @Volatile
    private var offlineHandoffAsked: Boolean = false
    private var networkLostGraceJob: Job? = null
    private var reconnectDebounceJob: Job? = null
    private var audioRouteDebounceJob: Job? = null
    private var lastTransport: Int? = null
    private var pendingTransport: Int? = null
    private var transportConfirmCount = 0

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** One stable listener — creating a new one on every play made Android fire
     *  AUDIOFOCUS_LOSS on the previous listener, which immediately paused playback. */
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasAudioFocus = false
                pauseTransport(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                playWhenFocusReturns = _state.value.isPlaying
                pauseTransport(userInitiated = false)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
                if (playWhenFocusReturns) {
                    playWhenFocusReturns = false
                    resumeTransport()
                }
            }
        }
    }

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** Set by [PlaybackService] so playback events can refresh the MediaSession. */
    @Volatile
    var onStateChanged: (() -> Unit)? = null

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pauseTransport(userInitiated = false)
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshExternalOutput()
            handleAudioRouteChange()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            refreshExternalOutput()
            handleAudioRouteChange()
        }
    }

    /**
     * Ask the platform whether anything but the phone's own speaker is connected.
     *
     * Deliberately "is a headset connected", not "is audio routed to it": the routed device is
     * unknown until something is playing, and the button is meant to answer "are my headphones
     * on?" — which has an answer while paused too.
     */
    private fun refreshExternalOutput() {
        val external = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                when (device.type) {
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    -> true
                    else -> false
                }
            }
        }.getOrDefault(false)
        if (_state.value.externalOutput == external) return
        _state.update { it.copy(externalOutput = external) }
        onStateChanged?.invoke()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // "A network attached" is not "the network works". This used to write
            // `networkOnline = true` outright and push it into the engine, which is wrong in exactly
            // the case the offline path exists for: airplane mode with Wi-Fi on, a captive portal, a
            // cellular radio registered with no data. Worse, it wrote from `scope.launch` while
            // `onCapabilitiesChanged` writes synchronously on the callback thread, so the honest
            // `false` could land *before* this stale `true` — and [applyNetworkCapabilities] returns
            // early on no change, so nothing came along to correct it. The capabilities are the
            // answer, and they are read here rather than assumed.
            val caps = connectivityManager.getNetworkCapabilities(network)
            if (caps != null) {
                applyNetworkCapabilities(caps)
                streamingPolicy.onCapabilitiesChanged(caps)
            }
            if (!_state.value.networkOnline) return
            networkLostGraceJob?.cancel()
            _state.update { recomputeStatusMessage(it.copy(sessionExpired = false)) }
            scope.launch {
                val current = _state.value
                val sessionDead = engineReady && !requireBackend().isSessionConnected()
                if (!current.connected || current.reconnecting || sessionDead) {
                    debouncedForceReconnect()
                }
            }
        }

        override fun onLost(network: Network) {
            networkLostGraceJob?.cancel()
            networkLostGraceJob = scope.launch {
                delay(NETWORK_HANDOFF_GRACE_MS)
                _state.update { recomputeStatusMessage(it.copy(networkOnline = false)) }
                // Nothing is interrupted here on purpose. A downloaded track is already playing off
                // disk, and a streaming one still has its read-ahead, so the switch to downloaded
                // audio waits until playback actually runs dry — see startStallWatchdog and the
                // engine's own Stopped handling.
                pushNetworkOnline(false)
                streamingPolicy.onOffline()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // THE missing wire. In a dead zone the radio stays registered, so `onLost` never fires
            // and this is the only callback that runs — and it never touched `networkOnline`, which
            // therefore stayed `true` for as long as the app lived. Everything that rescues playback
            // when the buffer runs dry is gated on that flag being false: the stall watchdog's
            // handover below, and the engine's own `Stopped` recovery. So audio simply stopped when
            // the read-ahead ended, with a downloaded copy of the same track sitting on disk.
            //
            // `isNetworkOnline()` was fixed to require VALIDATED, but it is only ever called at
            // attach and at sign-out — fixing the predicate without wiring it to the moment
            // validation actually changes fixed nothing.
            applyNetworkCapabilities(caps)
            streamingPolicy.onCapabilitiesChanged(caps)
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                    NetworkCapabilities.TRANSPORT_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                    NetworkCapabilities.TRANSPORT_CELLULAR
                else -> null
            }
            // Prefer cellular over a Wi‑Fi blip: do not treat a cellular→Wi‑Fi
            // handoff as confirmed until StreamingPolicy's 2‑minute Wi‑Fi gate.
            // Wi‑Fi→cellular (and same-transport) keep the existing sample confirm.
            val wifiHandoffBlocked = transport == NetworkCapabilities.TRANSPORT_WIFI &&
                lastTransport == NetworkCapabilities.TRANSPORT_CELLULAR &&
                !streamingPolicy.shouldPreferWifi(caps)
            if (wifiHandoffBlocked) {
                pendingTransport = null
                transportConfirmCount = 0
                return
            }
            considerTransportHandoff(transport, caps)
        }
    }

    /**
     * Push a capability change into [PlaybackUiState.networkOnline] and the engine.
     *
     * `NET_CAPABILITY_VALIDATED` is the platform's finding that traffic actually reached the
     * internet; `NET_CAPABILITY_INTERNET` is only the transport's claim about itself. Losing
     * validation while staying attached is exactly what a dead zone looks like, and it is the one
     * signal that arrives there.
     *
     * A transition to online cancels the [onLost] grace timer, so a brief drop that comes back does
     * not flip the flag a beat later and drag a healthy session into offline mode.
     */
    private fun applyNetworkCapabilities(caps: NetworkCapabilities) {
        val online = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (online) {
            OfflinePinHygiene.markOnline(appContext)
        }
        if (_state.value.networkOnline == online) return
        if (online) networkLostGraceJob?.cancel()
        // Each flip is a new situation. The latch exists to stop one stall re-asking every poll, not
        // to spend the app's one attempt on the first tunnel of the ride.
        offlineHandoffAsked = false
        android.util.Log.i("Playback", "networkOnline -> $online (validated=$online)")
        _state.update { recomputeStatusMessage(it.copy(networkOnline = online)) }
        pushNetworkOnline(online)
        if (!online) streamingPolicy.onOffline()
        onStateChanged?.invoke()
    }

    /**
     * Tell the engine what the network is doing, attached backend or not.
     *
     * Every push used to be behind `if (engineReady)`, and [requireBackend] throws before attach, so
     * the pushes were silently dropped for any engine the download service built first. The engine's
     * flag defaults to online, which made "dropped" mean "wrong".
     */
    private fun pushNetworkOnline(online: Boolean) {
        if (engineReady) {
            runCatching { requireBackend().setNetworkOnline(online) }
        } else {
            runCatching { PlaybackEngineHolder.engineOrNull()?.setNetworkOnline(online) }
        }
    }

    /**
     * Called when [StreamingPolicy]'s Wi‑Fi stability gate elapses so a deferred
     * cellular→Wi‑Fi session handoff can proceed without waiting for another
     * capabilities callback.
     */
    internal fun onWifiPreferGateElapsed(caps: NetworkCapabilities) {
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                NetworkCapabilities.TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                NetworkCapabilities.TRANSPORT_CELLULAR
            else -> null
        } ?: return
        considerTransportHandoff(transport, caps)
    }

    private fun considerTransportHandoff(transport: Int?, caps: NetworkCapabilities) {
        if (transport != null && lastTransport != null && transport != lastTransport) {
            if (pendingTransport == transport) {
                transportConfirmCount++
            } else {
                pendingTransport = transport
                transportConfirmCount = 1
            }
        } else if (transport == lastTransport) {
            pendingTransport = null
            transportConfirmCount = 0
        }
        lastTransport = transport ?: lastTransport
        if (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            transportConfirmCount >= TRANSPORT_CONFIRM_SAMPLES &&
            (_state.value.isPlaying || _state.value.reconnecting)
        ) {
            pendingTransport = null
            transportConfirmCount = 0
            debouncedForceReconnect()
        }
    }

    init {
        appContext.registerReceiver(
            becomingNoisyReceiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            Context.RECEIVER_NOT_EXPORTED,
        )
        // Was a when(backendChoice) with a second TIDAL arm; Spotify is the only backend
        // now, so the Step-2 Web API state feeds straight in.
        scope.launch {
            webApiAuth.sessionState.collect { state ->
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = state is com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                            webApiSessionState = state,
                        ),
                    )
                }
            }
        }
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    webApiReady = webApiAuth.sessionState.value is
                        com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                    webApiSessionState = webApiAuth.sessionState.value,
                    networkOnline = isNetworkOnline(),
                    loggedIn = hasCachedPlaybackCredentials(),
                    authInitialized = true,
                ),
            )
        }
        connectivityManager.registerDefaultNetworkCallback(networkCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            refreshExternalOutput()
        }
        connectivityManager.activeNetwork?.let { net ->
            connectivityManager.getNetworkCapabilities(net)?.let { caps ->
                streamingPolicy.onCapabilitiesChanged(caps)
            }
        }
    }

    /**
     * Hand back everything [init] took from the system, and stop [scope].
     *
     * Three callbacks are registered in the constructor — the becoming-noisy receiver, the default
     * network callback and the audio-device callback — and nothing ever took them back. They are
     * held by the platform rather than by this object, so an abandoned controller keeps being
     * called: the network callback in particular still runs [debouncedForceReconnect] against an
     * engine that has been torn down, which is the reconnect-churn shape this app has already been
     * bitten by once on the subway.
     *
     * Each unregister is its own `runCatching`. Unregistering a receiver that is not registered
     * throws, and one failure must not skip the other two.
     *
     * **Not called from `PlaybackService.onDestroy()`, on purpose.** The service does not own this:
     * the controller is a process-wide singleton reached through [get], the service stops itself
     * whenever nothing is playing (`onTaskRemoved`) and is started again by the next transport
     * action, and these registrations only ever happen in `init`, which never runs again for an
     * instance that already exists. Releasing on service destruction would quietly leave the rest
     * of the process with no audio-route and no network handling. [clearInstance] is the one path
     * that genuinely abandons the instance, and that is where this is called from.
     */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { appContext.unregisterReceiver(becomingNoisyReceiver) }
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { audioManager.unregisterAudioDeviceCallback(audioDeviceCallback) }
        }
        runCatching { scope.cancel() }
    }

    /** Wire the playback backend after lazy creation (login or first playback). */
    fun attachBackend(backend: PlaybackBackend) {
        if (engineReady) return
        this.backend = backend
        backend.setListener(this)
        // Spotify-only: bridge Login5 spclient metadata + live-session probe.
        (repository as? SpotifyRepository)?.let { spRepo ->
            backend.nativeMetadataGateway?.let { spRepo.nativeMetadata = it }
            spRepo.playbackSessionConnected = {
                engineReady && runCatching { requireBackend().isSessionConnected() }.getOrDefault(false)
            }
            // A downloaded item already has its title, art and duration on disk. Consulting the row
            // before the network is what lets an episode's duration — and therefore its progress bar,
            // times, scrub and skip buttons — survive with no connection at all.
            spRepo.localMetadata = { uri -> downloadedMetadata(uri) }
        }
        libraryRepository.playlistLibraryPageFetcher = { offset, limit ->
            repository.playlistLibraryPage(offset, limit)
        }
        engineReady = true
        runCatching { requireBackend().setAppForeground(appForegroundRequested) }
        runCatching { requireBackend().setNetworkOnline(_state.value.networkOnline) }
        val alreadyLoggedIn = backend.isLoggedIn()
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    loggedIn = alreadyLoggedIn,
                    authInitialized = true,
                    connected = backend.isSessionConnected(),
                ),
            )
        }
        applyPendingSettings()
        SleepTimer.registerOutput(sleepOutput)
        startStallWatchdog()
        if (alreadyLoggedIn) {
            warmSpclientSessionAsync()
        }
    }

    fun setAppForeground(foreground: Boolean) {
        appForegroundRequested = foreground
        if (engineReady) {
            runCatching { requireBackend().setAppForeground(foreground) }
        }
    }

    /** Fire-and-forget warm for lifecycle / attach paths. */
    fun warmSpclientSessionAsync() {
        scope.launch {
            warmSpclientSession()
        }
    }

    /**
     * Ensure Step 1 librespot session is live. Idempotent; safe to call on every app open.
     * Does not throw — callers inspect [WarmResult].
     */
    suspend fun warmSpclientSession(): WarmResult = withContext(Dispatchers.IO) {
        if (signingOut) return@withContext WarmResult.NotSignedIn
        sessionLifecycleMutex.withLock {
            if (signingOut) return@withContext WarmResult.NotSignedIn
            if (!ensureEngineReady()) {
                return@withContext WarmResult.Failed("Playback service not ready")
            }
            if (!requireBackend().isLoggedIn()) {
                return@withContext WarmResult.NotSignedIn
            }
            return@withContext runCatching { requireBackend().ensurePlaybackReady() }.fold(
                onSuccess = {
                    if (!signingOut) {
                        syncConnectedFromEngine()
                        onSessionRestored?.invoke()
                    }
                    WarmResult.Success
                },
                onFailure = { e ->
                    if (!signingOut) {
                        syncConnectedFromEngine()
                    }
                    WarmResult.Failed(mapSpotifyError(e))
                },
            )
        }
    }

    private fun syncConnectedFromEngine() {
        if (!engineReady) return
        val connected = runCatching { requireBackend().isSessionConnected() }.getOrDefault(false)
        _state.update {
            recomputeStatusMessage(it.copy(connected = connected, reconnecting = false))
        }
    }

    private fun hasCachedPlaybackCredentials(): Boolean =
        File(appContext.filesDir, "spotify-cache/creds/credentials.json").exists()

    /** Belt-and-suspenders: ensure disk creds are gone even if the engine was never attached. */
    private fun clearPlaybackCredentialFiles() {
        val credDir = File(appContext.filesDir, "spotify-cache/creds")
        listOf(
            "credentials.json",
            "oauth_refresh_token",
            "oauth_access_cache.json",
        ).forEach { name -> File(credDir, name).delete() }
    }

    fun isUnmeteredNetwork(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun applyPendingSettings() {
        if (!engineReady) return
        val eng = requireBackend()
        pendingSettings.streamingQuality?.let { eng.setStreamingQuality(it) }
        pendingSettings.downloadQuality?.let { eng.setDownloadQuality(it) }
        pendingSettings.gaplessEnabled?.let { eng.setGaplessEnabled(it) }
        pendingSettings.normalizationEnabled?.let { eng.setNormalizationEnabled(it) }
        pendingSettings.normalizationType?.let { eng.setNormalizationType(it) }
        pendingSettings.proxy?.let { eng.setProxy(it) }
    }

    private fun launchTransport(block: suspend () -> Unit): Job =
        scope.launch {
            transportMutex.withLock { block() }
        }

    /**
     * Launch a user-initiated transport command that supersedes any prior pending
     * one. Cancels the previous [transportJob] so rapid skips collapse to the last
     * intent. While reconnecting, waits out a short coalesce window first so a
     * flurry of taps on a bad connection triggers at most one native rebuild/load
     * for the final target instead of one per tap.
     */
    private fun launchTransportExclusive(block: suspend () -> Unit): Job {
        transportJob?.cancel()
        val job = scope.launch {
            if (_state.value.reconnecting) {
                delay(TRANSPORT_COALESCE_MS)
            }
            transportMutex.withLock { block() }
        }
        transportJob = job
        return job
    }

    private fun requireBackend(): PlaybackBackend {
        check(engineReady) { "Playback engine not ready — call ensureServiceStarted() first" }
        return backend
    }

    /** Exposed for [StreamingPolicy]. */
    internal val appContextInternal: Context get() = appContext

    /**
     * True when the track playing right now has a completed download on disk.
     *
     * Asked before anything that would either fetch it again or interrupt it. The engine is the
     * authority rather than the Room download table: it is the same `is_downloaded` check the player
     * itself uses to pick a pin over the CDN, so the two can never disagree.
     */
    internal fun currentTrackDownloaded(): Boolean {
        if (!engineReady) return false
        val uri = _state.value.currentUri ?: return false
        return runCatching { requireBackend().isTrackDownloaded(uri) }.getOrDefault(false)
    }

    fun bufferCurrentToEnd() {
        if (!engineReady) return
        // A downloaded track is already complete on disk; banking it would re-fetch bytes we have.
        if (currentTrackDownloaded()) return
        runCatching { requireBackend().bufferCurrentToEnd() }
    }

    fun prefetchUpcoming(ahead: Int) {
        if (!engineReady || ahead <= 0) return
        runCatching { requireBackend().prefetchUpcoming(ahead.toUInt()) }
    }

    private fun handleAudioRouteChange() {
        if (!engineReady) return
        if (BuildConfig.USE_AUDIOTRACK_SINK) {
            // Path C: PhonoAudioTrackSink owns routing via OnRoutingChangedListener.
            // Only recreate the Rust sink wrapper if Kotlin metrics show repeated failures.
            audioRouteDebounceJob?.cancel()
            audioRouteDebounceJob = scope.launch {
                delay(AUDIO_ROUTE_DEBOUNCE_MS)
                val deadObjects = runCatching {
                    PhonoAudioTrackSink.getDeadObjectCount()
                }.getOrDefault(0)
                if (deadObjects > 0) {
                    runCatching { requireBackend().recreateAudioSink() }
                }
            }
            return
        }
        audioRouteDebounceJob?.cancel()
        audioRouteDebounceJob = scope.launch {
            delay(AUDIO_ROUTE_DEBOUNCE_MS)
            val wasPlaying = _state.value.isPlaying
            if (wasPlaying) pauseTransport(userInitiated = false)
            runCatching { requireBackend().recreateAudioSink() }
            if (wasPlaying && hasAudioFocus) resumeTransport()
        }
    }

    private fun debouncedForceReconnect() {
        reconnectDebounceJob?.cancel()
        reconnectDebounceJob = scope.launch {
            delay(RECONNECT_DEBOUNCE_MS)
            // Offline there is nothing to reconnect to, and the attempt is not free: the call below
            // blocks inside a retrying access-point connect while holding `transportMutex`, which is
            // the same lock a tap on a downloaded track needs. That is a spinner over a file on disk.
            // The engine defers the rebuild it is owed; this side simply does not ask.
            if (!_state.value.networkOnline) {
                android.util.Log.i("Playback", "skipping reconnect: offline")
                return@launch
            }
            // Note this can fire once a station on a subway ride, and that a rebuild is destructive
            // — the Active is torn down before the new session connects. Deliberately NOT filtered
            // here: the engine's `force_reconnect_check` defers the rebuild when downloaded audio is
            // playing and runs it at the next pause or track change. Dropping the request on this
            // side instead would leave nothing to remember that a rebuild is still owed.
            // Serialize with user transport so a network-handoff session shutdown
            // cannot land in the middle of a play/skip at the FFI boundary.
            transportMutex.withLock {
                if (engineReady) {
                    runCatching { requireBackend().forceReconnectCheck() }
                }
            }
        }
    }

    private fun startStallWatchdog() {
        stallWatchdogJob?.cancel()
        stallWatchdogJob = scope.launch {
            var loadingSinceMs = 0L
            while (isActive) {
                delay(STALL_POLL_MS)
                if (!engineReady) continue
                val s = _state.value

                // A load that never finishes has to be given up on.
                //
                // `isLoading` is set unconditionally when play/resume is issued and is only ever
                // cleared by a *player event* — onTrackChanged, onPlaying, onBuffering(false). A
                // resume that no-ops (idle player after a rebuild) emits none of those, so the flag
                // latched forever. That also disabled this watchdog, since it used to `continue` on
                // `isLoading` — the one mechanism that could have recovered was switched off by the
                // symptom. Self-sealing, and the reason it needed a process restart.
                if (s.isLoading) {
                    if (loadingSinceMs == 0L) loadingSinceMs = System.currentTimeMillis()
                    if (System.currentTimeMillis() - loadingSinceMs > LOADING_STUCK_MS) {
                        android.util.Log.w("Playback", "load stuck; clearing and retrying offline")
                        loadingSinceMs = 0L
                        _state.update {
                            recomputeStatusMessage(it.copy(isLoading = false, isBuffering = false))
                        }
                        offlineHandoffAsked = false
                        handOffToLocalAudio(believedOffline = !_state.value.networkOnline)
                        onStateChanged?.invoke()
                    }
                    continue
                }
                loadingSinceMs = 0L

                if (!playbackPulseSeen) continue
                if (!s.isPlaying || s.currentUri == null) continue
                val stalledFor = System.currentTimeMillis() - lastPositionAtMs
                when {
                    stalledFor > STALL_BUFFERING_MS -> {
                        // Buffer only — do NOT forceReconnectCheck here; shutting down the
                        // session mid-play drops Active and can exit(1) in librespot player.
                        setBuffering(true)
                        // Offline there is nothing to wait for: the fetch behind this stall cannot
                        // finish, and librespot will not abandon it for another download_timeout, so
                        // the engine's own Stopped recovery is half a minute away. Ask for the
                        // handover to downloaded audio now. Once per stall, or a queue with nothing
                        // downloaded would re-ask every poll and re-raise the same error.
                        when (
                            OfflineHandoff.decide(
                                stalledForMs = stalledFor,
                                networkOnline = s.networkOnline,
                                alreadyAsked = offlineHandoffAsked,
                                currentTrackDownloaded = currentTrackDownloaded(),
                                bufferingThresholdMs = STALL_BUFFERING_MS,
                                localHandoffThresholdMs = STALL_LOCAL_HANDOFF_MS,
                            )
                        ) {
                            OfflineHandoff.Action.Wait -> Unit
                            OfflineHandoff.Action.SwitchAndReport -> {
                                offlineHandoffAsked = true
                                offlineHandoffAsked = handOffToLocalAudio(believedOffline = true)
                            }
                            OfflineHandoff.Action.SwitchQuietly -> {
                                offlineHandoffAsked = true
                                offlineHandoffAsked = handOffToLocalAudio(believedOffline = false)
                            }
                        }
                        streamingPolicy.onPlaybackStall()
                    }
                    else -> if (s.isBuffering) setBuffering(false)
                }
            }
        }
    }

    /**
     * Ask the engine to continue from downloaded audio, and surface it if it cannot.
     *
     * The engine reports failure rather than throwing, because "nothing in the queue is downloaded"
     * is an answer the user needs — the alternative, and what shipped, was a player that sat
     * buffering with no explanation until it was force-stopped.
     */
    /**
     * Ask the engine to continue from downloaded audio, and surface it if it cannot.
     *
     * Returns whether the answer was **definitive** — i.e. whether the caller should stop asking. A
     * `false` from the engine while it happens to have no Active (the window during a rebuild) is not
     * an answer, and treating it as one burned the single allowed attempt for the whole stall.
     */
    private fun handOffToLocalAudio(believedOffline: Boolean): Boolean {
        if (!engineReady) return false
        val result = runCatching { requireBackend().switchToLocalAudio() }
        val switched = result.getOrNull()
        if (switched == null) {
            // The call itself failed — no information about whether a download exists.
            offlineHandoffAsked = false
            return false
        }
        if (switched) {
            android.util.Log.i("Playback", "handed off to downloaded audio")
            return true
        }
        // Nothing downloaded to fall back to. Only claim "offline" when that is actually what we
        // think is happening: a long stall on genuinely slow data is still worth waiting out, and
        // telling the user their library is unavailable would be wrong.
        if (!believedOffline) return true
        _state.update {
            it.copy(isPlaying = false, isBuffering = false, error = "Not available offline.")
        }
        onStateChanged?.invoke()
        return true
    }

    private fun markPlaybackPulse() {
        playbackPulseSeen = true
        offlineHandoffAsked = false
        lastPositionAtMs = System.currentTimeMillis()
    }

    private fun resetPlaybackPulse() {
        playbackPulseSeen = false
        lastPositionAtMs = 0
    }

    private fun setBuffering(buffering: Boolean) {
        if (_state.value.isBuffering == buffering) return
        _state.update { it.copy(isBuffering = buffering) }
        onStateChanged?.invoke()
    }

    // --- Auth ---------------------------------------------------------------

    /** Bring up the engine off the main thread, then return the authorize URL. */
    suspend fun beginLogin(): String = withContext(Dispatchers.IO) {
        ensureEngineReady()
        requireBackend().beginLogin()
    }

    fun clearLoginError() {
        _state.update { recomputeStatusMessage(it.copy(error = null)) }
    }

    fun completeLogin(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        ensureEngineReady()
        if (!engineReady) {
            onResult(Result.failure(IllegalStateException("Playback engine not ready")))
            return
        }
        scope.launch {
            val result = sessionLifecycleMutex.withLock {
                runCatching { requireBackend().loginWithOauthCode(code, state) }
            }
            result.onFailure { e ->
                android.util.Log.e("Playback", "completeLogin failed: ${e.message}", e)
                val sessionExpired = e is SpotifyException.Auth
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            loggedIn = requireBackend().isLoggedIn(),
                            sessionExpired = sessionExpired,
                            error = mapSpotifyError(e),
                        ),
                    )
                }
            }
            result.onSuccess {
                android.util.Log.i("Playback", "completeLogin ok")
                _state.update {
                    recomputeStatusMessage(
                        it.copy(loggedIn = true, sessionExpired = false, error = null),
                    )
                }
            }
            onResult(result)
        }
    }

    fun tryCachedLogin(onResult: (Boolean) -> Unit) {
        if (!engineReady) {
            ensureEngineReady()
        }
        if (!engineReady) {
            onResult(false)
            return
        }
        scope.launch {
            val ok = sessionLifecycleMutex.withLock {
                runCatching { requireBackend().loginWithCachedCredentials() }.getOrDefault(false)
            }
            _state.update {
                it.copy(loggedIn = requireBackend().isLoggedIn(), authInitialized = true)
            }
            onResult(ok)
        }
    }

    fun logout(onSignedOut: (() -> Unit)? = null) {
        scope.launch {
            signingOut = true
            try {
                sessionLifecycleMutex.withLock {
                    sessionCoordinator.signOut(
                        onCancelInFlight = {
                            // Cancel every job that can still reach into the native engine,
                            // then join (with a short timeout) so logout always reaches
                            // the backend picker even if a transport job is stuck.
                            reconnectDebounceJob?.cancel()
                            audioRouteDebounceJob?.cancel()
                            networkLostGraceJob?.cancel()
                            playlistUriIndexJob?.cancel()
                            transportJob?.cancel()
                            withTimeoutOrNull(LOGOUT_JOIN_TIMEOUT_MS) { transportJob?.join() }
                            withTimeoutOrNull(LOGOUT_JOIN_TIMEOUT_MS) { reconnectDebounceJob?.join() }
                            withTimeoutOrNull(LOGOUT_JOIN_TIMEOUT_MS) { audioRouteDebounceJob?.join() }
                        },
                    )
                }
                abandonFocus()
                runCatching {
                    com.lightphone.spotify.ui.WebViewAuthCleanup.clear()
                }
                _state.value = recomputeStatusMessage(
                    PlaybackUiState(
                        loggedIn = false,
                        authInitialized = true,
                        // Spotify resets to NotConfigured and re-gates on Step 2.
                        webApiReady = false,
                        webApiSessionState =
                            com.lightphone.spotify.data.webapi.WebApiSessionState.NotConfigured,
                        networkOnline = isNetworkOnline(),
                    ),
                )
                onStateChanged?.invoke()
                onSignedOut?.invoke()
            } finally {
                signingOut = false
            }
        }
    }

    // --- Web API auth (Step 2) ----------------------------------------------

    fun hasWebApiCredentials(): Boolean = webApiAuth.hasCredentials()

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        webApiAuth.saveCredentials(clientId, clientSecret)
    }

    /**
     * A bearer for the Web API, or null when there is none to be had.
     *
     * Only for the ZeroConf `accesstoken` claim flow, which needs the raw token rather than a
     * request signed with it. Blocking and network-touching (it may refresh), so call it off the main
     * thread.
     */
    fun webApiBearerOrNull(): String? = runCatching { webApiAuth.currentBearer() }.getOrNull()

    fun buildWebApiAuthorizeUrl(): String = webApiAuth.buildAuthorizeUrl()

    fun completeWebApiAuth(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        scope.launch {
            val result = webApiAuth.exchangeCode(code, state)
            result.onSuccess {
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = true,
                            webApiSessionState = com.lightphone.spotify.data.webapi.WebApiSessionState.Authorized,
                            error = null,
                        ),
                    )
                }
            }
            result.onFailure { e ->
                _state.update {
                    recomputeStatusMessage(
                        it.copy(
                            webApiReady = false,
                            error = mapWebApiError(e),
                        ),
                    )
                }
            }
            onResult(result)
        }
    }

    // --- Podcasts -----------------------------------------------------------
    // Straight passthroughs to the Web API. They live here because webApi is private to the
    // controller, and because the auto-downloader needs them from outside any ViewModel.

    suspend fun savedShowsPage(offset: Int) = webApi.savedShowsPage(offset)

    suspend fun savedEpisodesPage(offset: Int) = webApi.savedEpisodesPage(offset)

    suspend fun showEpisodes(showId: String) = webApi.showEpisodes(showId)

    suspend fun showEpisodesPage(showId: String, offset: Int, limit: Int) =
        webApi.showEpisodesPage(showId, offset, limit)

    suspend fun show(showId: String) = webApi.show(showId)

    fun logoutWebApi() {
        webApiAuth.clearAll()
        _state.update {
            recomputeStatusMessage(it.copy(webApiReady = false))
        }
    }

    /**
     * Drop the Web API token but keep the user's client id/secret, so the app falls back
     * to the Step 2 authorize screen without making them re-enter credentials.
     *
     * Used when a stored token predates a scope this fork added (Spotify Connect), which
     * is the one failure that cannot be fixed by refreshing — a refresh returns a token
     * with the original grant's scopes.
     */
    fun reauthorizeWebApi() {
        webApiAuth.clearTokens()
        _state.update {
            recomputeStatusMessage(it.copy(webApiReady = false))
        }
    }

    // --- Transport ----------------------------------------------------------

    /**
     * Start a queue.
     *
     * [startPositionMs] is handed to the engine's loader rather than applied by a follow-up seek — see
     * [PlaybackBackend.playUris]. It is also what the optimistic UI state is seeded with, so the
     * progress bar starts where the audio will, instead of snapping from 0 to the resume point.
     */
    fun play(
        tracks: List<TrackMetadata>,
        startIndex: Int,
        contextLabel: String? = null,
        startPositionMs: Long = 0L,
    ) {
        ensureServiceStarted()
        tracks.forEach { trackMetadata[normalizeUri(it.uri)] = it }
        tracks.getOrNull(startIndex)?.let { track ->
            _state.update {
                it.copy(
                    currentUri = normalizeUri(track.uri),
                    title = track.title,
                    artist = track.artists,
                    album = track.album.takeIf { it.isNotBlank() },
                    artUrl = track.artUrl,
                    albumId = track.albumId,
                    durationMs = track.durationMs,
                    isLoading = true,
                    isPlaying = false,
                    positionMs = startPositionMs,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.OFF,
                    error = null,
                )
            }
            onStateChanged?.invoke()
        }
        resetOutputGain()
        val uris = tracks.map { normalizeUri(it.uri) }
        transportJob?.cancel()
        transportJob = launchTransport {
            if (!ensureAudioFocus()) {
                android.util.Log.w("Playback", "audio focus denied")
                _state.update { it.copy(isPlaying = false, error = "Audio focus denied") }
                onStateChanged?.invoke()
            } else if (!ensureEngineReady()) {
                android.util.Log.w("Playback", "engine not ready")
                _state.update { it.copy(isPlaying = false, error = "Playback service not ready") }
                onStateChanged?.invoke()
            } else {
                resetPlaybackPulse()
                runCatching {
                    requireBackend().playUris(
                        uris,
                        startIndex.toUInt(),
                        contextLabel,
                        startPositionMs.coerceAtLeast(0L).toUInt(),
                    )
                }
                    .onSuccess {
                        android.util.Log.i(
                            "Playback",
                            "playUris index=$startIndex uri=${uris.getOrNull(startIndex)}",
                        )
                        _state.update { it.copy(isLoading = true) }
                        onStateChanged?.invoke()
                    }
                    .onFailure { e ->
                        android.util.Log.e("Playback", "playUris failed", e)
                        val msg = mapPlayFailure(e)
                        _state.update { it.copy(isPlaying = false, isLoading = false, error = msg) }
                        onStateChanged?.invoke()
                    }
            }
        }
    }

    /**
     * Transport for callers outside the UI — the lock-screen overlay, and anything else that
     * only holds this controller.
     *
     * [AppViewModel] has branched every button on "is a Connect device active" since casting
     * shipped, but that routing lived in the ViewModel, so the lock-screen row talked straight
     * to the local engine: with music on a speaker, its buttons drove a paused local player and
     * looked dead. Same lesson as the pinned-playback guard — a rule kept at one call site is
     * not a rule — so the destination check lives here, where anything holding a controller
     * gets it.
     */
    fun routedPlayPause() {
        if (connect.state.value.isRemote) {
            if (connect.remotePlayback.value?.isPlaying == true) connect.pause() else connect.play()
        } else {
            if (_state.value.isPlaying) pause() else resume()
        }
    }

    /**
     * Jump [deltaMs] from where playback is now.
     *
     * Lives here rather than in the ViewModel because the lock-screen row needs the same jump for
     * podcasts, and the two guards below are the sort that get forgotten by a second caller: a
     * blind seek with no duration would jump 15 seconds into a track nobody has started, and
     * landing exactly on the duration ends the track — "forward 15 seconds" must never be a skip.
     */
    fun seekBy(deltaMs: Long) {
        val duration = _state.value.durationMs
        if (duration <= 0L) return
        val target = (_state.value.positionMs + deltaMs)
            // Floored, because coerceIn throws on an inverted range and a sub-second duration
            // would give it one.
            .coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        seek(target)
    }

    fun routedNext() {
        if (connect.state.value.isRemote) connect.next() else next()
    }

    fun routedPrevious() {
        if (connect.state.value.isRemote) connect.previous() else previous()
    }

    /** What the transport glyph should say, wherever the audio is. */
    fun routedIsPlaying(): Boolean =
        if (connect.state.value.isRemote) {
            connect.remotePlayback.value?.isPlaying == true
        } else {
            _state.value.isPlaying
        }

    fun resume() = resumeTransport()

    fun pause() = pauseTransport(userInitiated = true)

    private fun resumeTransport() {
        // Whatever a fade left behind belongs to the thing that was playing before, not to this.
        resetOutputGain()
        launchTransport {
            if (ensureEngineReady() && ensureAudioFocus()) {
                requireBackend().resume()
                _state.update { it.copy(isLoading = true) }
                onStateChanged?.invoke()
            }
        }
    }

    /** Pause the engine and mirror state locally (don't wait on Mercury/player events). */
    private fun pauseTransport(userInitiated: Boolean) {
        launchTransport {
            if (engineReady) {
                requireBackend().pause()
                _state.update { it.copy(isPlaying = false) }
                onStateChanged?.invoke()
                if (userInitiated) {
                    // Keep focus so resume is instant; abandon only on end-of-queue.
                }
            }
        }
    }

    fun next() = launchTransportExclusive {
        if (ensureEngineReady()) {
            requireBackend().next()
            syncPlaybackModes()
        }
    }
    fun previous() = launchTransportExclusive {
        if (ensureEngineReady()) {
            requireBackend().previous()
            syncPlaybackModes()
        }
    }
    fun seek(positionMs: Long) = launchTransportExclusive {
        val target = positionMs.coerceAtLeast(0L)
        lastPositionMs = target
        pendingSeekTargetMs = target
        pendingSeekSinceMs = SystemClock.elapsedRealtime()
        _state.update { it.copy(positionMs = target) }
        onStateChanged?.invoke()
        if (ensureEngineReady()) {
            requireBackend().seek(target.toUInt())
        }
    }
    fun toggleShuffle() = scope.launch {
        if (!ensureEngineReady()) return@launch
        val enabled = requireBackend().toggleShuffle()
        _state.update { it.copy(shuffleEnabled = enabled) }
        onStateChanged?.invoke()
    }
    fun toggleRepeat() = scope.launch {
        if (!ensureEngineReady()) return@launch
        val mode = requireBackend().toggleRepeat()
        _state.update { it.copy(repeatMode = mode) }
        onStateChanged?.invoke()
    }
    fun refreshQueue() {
        if (!engineReady) return
        val snapshot = requireBackend().getQueue()
        val queue = QueueViewState(
            nowPlaying = snapshot.nowPlayingUri?.let { uriToQueueItem(normalizeUri(it)) },
            nextInQueue = snapshot.nextInQueue.map { uriToQueueItem(normalizeUri(it)) },
            contextLabel = snapshot.contextLabel,
            nextFromContext = snapshot.nextFromContext.map { uriToQueueItem(normalizeUri(it)) },
        )
        _state.update { it.copy(queue = queue) }
        onStateChanged?.invoke()
        enrichQueueMetadata(queue.allUris())
    }

    private fun QueueViewState.allUris(): List<String> =
        buildList {
            nowPlaying?.uri?.let { add(it) }
            addAll(nextInQueue.map { it.uri })
            addAll(nextFromContext.map { it.uri })
        }

    private fun uriToQueueItem(uri: String): QueueUiItem {
        val cached = trackMetadata[uri]
        return QueueUiItem(
            uri = uri,
            title = cached?.title ?: "…",
            artists = cached?.artists.orEmpty(),
            durationMs = cached?.durationMs ?: 0L,
        )
    }

    /**
     * `withContext(Dispatchers.IO)` is not decoration. The repository call underneath used to reach
     * a `runBlocking` in the Web API client, and the caller is `AppViewModel`'s
     * `viewModelScope.launch` — the main dispatcher — so a cache miss here parked the UI thread on
     * a Spotify round trip. The client is suspend now; the hop stays because the cache lookup and
     * the network call share one entry point and only one of them is cheap.
     */
    suspend fun trackMetadataForUri(uri: String): TrackMetadata? =
        withContext(Dispatchers.IO) {
            val normalized = normalizeUri(uri)
            trackMetadata[normalized] ?: repository.trackMetadataForUri(normalized)
        }

    private fun enrichQueueMetadata(uris: List<String>) {
        val missing = uris.filter { trackMetadata[it] == null }
        if (missing.isEmpty()) return
        scope.launch {
            for (uri in missing) {
                runCatching { repository.trackMetadataForUri(uri) }
                    .onSuccess { meta ->
                        if (meta != null) {
                            trackMetadata[uri] = meta
                            refreshQueue()
                        }
                    }
            }
        }
    }

    fun addToQueue(track: TrackMetadata) {
        trackMetadata[normalizeUri(track.uri)] = track
        if (!engineReady) {
            play(listOf(track), 0, track.album.ifBlank { track.title })
            return
        }
        val snapshot = requireBackend().getQueue()
        if (_state.value.currentUri == null && snapshot.nowPlayingUri == null) {
            play(listOf(track), 0, track.album.ifBlank { track.title })
            return
        }
        scope.launch {
            runCatching { requireBackend().addToQueue(normalizeUri(track.uri)) }
                .onSuccess { refreshQueue() }
                .onFailure { e ->
                    android.util.Log.w("Playback", "addToQueue failed", e)
                    _state.update { it.copy(error = e.message) }
                }
        }
    }

    fun clearManualQueue() = scope.launch {
        if (!engineReady) return@launch
        requireBackend().clearManualQueue()
        refreshQueue()
    }

    fun moveQueueItemUp(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveQueueItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemUp failed", e) }
    }

    fun moveQueueItemDown(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveQueueItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveQueueItemDown failed", e) }
    }

    fun moveContextItemUp(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveContextItemUp(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemUp failed", e) }
    }

    fun moveContextItemDown(index: Int) = scope.launch {
        if (!engineReady) return@launch
        runCatching { requireBackend().moveContextItemDown(index.toUInt()) }
            .onSuccess { refreshQueue() }
            .onFailure { e -> android.util.Log.w("Playback", "moveContextItemDown failed", e) }
    }

    fun loadSettings(): SettingsSnapshot {
        if (!engineReady) {
            return SettingsSnapshot(
                streamingQuality = pendingSettings.streamingQuality ?: StreamingQuality.NORMAL,
                gaplessEnabled = pendingSettings.gaplessEnabled ?: true,
                normalizationEnabled = pendingSettings.normalizationEnabled ?: false,
                normalizationType = pendingSettings.normalizationType ?: NormalizationType.AUTO,
                proxy = pendingSettings.proxy,
            )
        }
        val eng = requireBackend()
        return SettingsSnapshot(
            streamingQuality = eng.getStreamingQuality(),
            gaplessEnabled = eng.getGaplessEnabled(),
            normalizationEnabled = eng.getNormalizationEnabled(),
            normalizationType = eng.getNormalizationType(),
            proxy = eng.getProxy(),
        )
    }

    fun setStreamingQuality(quality: StreamingQuality) {
        pendingSettings.streamingQuality = quality
        scope.launch {
            if (ensureEngineReady()) requireBackend().setStreamingQuality(quality)
        }
    }

    /**
     * Quality string passed to [OfflineDownloadCenter.download] / collection enqueue.
     * Spotify: `LOW` / `NORMAL` / `HIGH`.
     */
    fun downloadQualityApiValue(): String = getSpotifyDownloadQuality().name

    fun getSpotifyDownloadQuality(): StreamingQuality =
        pendingSettings.downloadQuality
            ?: runCatching {
                if (engineReady) requireBackend().getDownloadQuality() else null
            }.getOrNull()
            ?: StreamingQuality.HIGH

    fun setSpotifyDownloadQuality(quality: StreamingQuality) {
        pendingSettings.downloadQuality = quality
        scope.launch {
            if (ensureEngineReady()) requireBackend().setDownloadQuality(quality)
        }
    }

    /**
     * Gapless, as the engine should be configured given the fade setting too.
     *
     * A fade between tracks needs the tight seam gapless provides, so setting one turns gapless on
     * and keeps it on — see [TrackFade.effectiveGapless]. The write is real rather than hidden: the
     * settings screen shows gapless as on and says why, instead of a toggle that reads off while
     * the player behaves as if it were on.
     */
    fun setGaplessEnabled(enabled: Boolean) {
        val effective = TrackFade.effectiveGapless(enabled, TrackFadeSettings.seconds)
        pendingSettings.gaplessEnabled = effective
        scope.launch {
            if (ensureEngineReady()) requireBackend().setGaplessEnabled(effective)
        }
    }

    fun setNormalizationEnabled(enabled: Boolean) {
        pendingSettings.normalizationEnabled = enabled
        scope.launch {
            if (ensureEngineReady()) requireBackend().setNormalizationEnabled(enabled)
        }
    }

    fun setNormalizationType(type: NormalizationType) {
        pendingSettings.normalizationType = type
        scope.launch {
            if (ensureEngineReady()) requireBackend().setNormalizationType(type)
        }
    }

    fun setProxy(proxy: String?) {
        pendingSettings.proxy = proxy
        scope.launch {
            if (ensureEngineReady()) requireBackend().setProxy(proxy)
        }
    }

    fun clearAudioCache() = scope.launch {
        if (ensureEngineReady()) requireBackend().clearAudioCache()
    }

    /** Search tracks via Web API (catalog search, track type only). */
    suspend fun searchTracks(query: String, limit: Int = 25): Result<List<TrackMetadata>> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                val results = repository.search(query, limitPerType = limit.coerceIn(1, 10))
                val tracks = results.tracks.map { it.toMetadata() }.take(limit)
                android.util.Log.i(
                    "Search",
                    "searchTracks returned ${tracks.size} results for '$query'",
                )
                Result.success(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Search", "searchTracks failed", e)
                Result.failure(Exception(mapWebApiError(e)))
            }
        }

    fun likedTracksUiFlow(): Flow<Triple<List<LikedTrackEntity>, Int, Boolean>> =
        libraryRepository.likedTracksUiFlow()

    fun savedAlbumsUiFlow(): Flow<Triple<List<SavedAlbumEntity>, Int, Boolean>> =
        libraryRepository.savedAlbumsUiFlow()

    suspend fun refreshLikedTracks(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshLikedTracks()
        }

    suspend fun likedTracksNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.likedTracksNeedsFill()
        }

    suspend fun appendLikedTracks(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendLikedTracks()
        }

    suspend fun refreshSavedAlbums(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshSavedAlbums()
        }

    suspend fun savedAlbumsNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.savedAlbumsNeedsFill()
        }

    suspend fun appendSavedAlbums(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendSavedAlbums()
        }

    suspend fun fillRemainingLikedTracks(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingLikedTracks()
        }

    suspend fun fillRemainingSavedAlbums(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingSavedAlbums()
        }

    suspend fun likedTracksForPlayback(fromIndex: Int): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.likedTracksForPlayback(fromIndex)
        }

    fun playlistsUiFlow(): Flow<Triple<List<PlaylistEntity>, Int, Boolean>> =
        libraryRepository.playlistsUiFlow()

    suspend fun refreshPlaylists(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.refreshPlaylists()
        }

    suspend fun playlistsNeedsFill(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.playlistsNeedsFill()
        }

    suspend fun appendPlaylists(): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.appendPlaylists()
        }

    suspend fun fillRemainingPlaylists(): Int =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.fillRemainingPlaylists()
        }

    private var playlistUriIndexJob: Job? = null
    @Volatile
    private var playlistUriIndexPending = false

    private data class PendingPlaybackSettings(
        var streamingQuality: StreamingQuality? = null,
        var downloadQuality: StreamingQuality? = null,
        var gaplessEnabled: Boolean? = null,
        var normalizationEnabled: Boolean? = null,
        var normalizationType: NormalizationType? = null,
        var proxy: String? = null,
    )

    private val pendingSettings = PendingPlaybackSettings()

    /** Snapshot-gated rebuild of playlist track URI index (lazy — playlist picker). */
    fun schedulePlaylistUriIndexSync() {
        if (playlistUriIndexJob?.isActive == true) {
            playlistUriIndexPending = true
            return
        }
        playlistUriIndexPending = false
        playlistUriIndexJob = scope.launch {
            runCatching { repository.syncPlaylistUriIndex() }
                .onFailure { e ->
                    android.util.Log.w("PlaylistUriIndex", "sync failed", e)
                }
            if (playlistUriIndexPending) {
                playlistUriIndexPending = false
                schedulePlaylistUriIndexSync()
            }
        }
    }

    suspend fun playlistDetail(playlistId: String): PlaylistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistDetail(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "playlistDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun createPlaylist(name: String, isPublic: Boolean): SpotifyPlaylistSimple =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.createPlaylist(name, isPublic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun renamePlaylist(playlistId: String, name: String): SpotifyPlaylistDetail =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.renamePlaylist(playlistId, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun addTrackToPlaylist(
        playlistId: String,
        uri: String,
        snapshotId: String? = null,
    ): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.addTrackToPlaylist(playlistId, uri, snapshotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeTrackFromPlaylist(playlistId: String, uri: String, snapshotId: String?): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrackFromPlaylist(playlistId, uri, snapshotId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun reorderPlaylistTrack(
        playlistId: String,
        fromIndex: Int,
        toIndex: Int,
        snapshotId: String?,
    ): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        try {
            repository.reorderPlaylistTrack(playlistId, fromIndex, toIndex, snapshotId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
        }
    }

    suspend fun editablePlaylists(userId: String? = null): List<PlaylistEntity> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.editablePlaylists(userId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun currentUserId(): String =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.currentUserIdSuspend()
        }

    suspend fun albumDetail(albumId: String): AlbumDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumDetail(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun artistDetail(artistId: String): ArtistDetailResult =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.artistDetail(artistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "artistDetail failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun search(query: String, limitPerType: Int = 8): SearchResults =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.search(query, limitPerType)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Search", "search failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun playlistTracks(playlistId: String, limit: Int = 100): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.playlistTracks(playlistId, limit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Search", "playlistTracks failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun albumTracks(albumId: String): List<TrackMetadata> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.albumTracks(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "albumTracks failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun isTrackSaved(uri: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.isTrackSaved(uri)
        }

    suspend fun isSavedAlbumCached(albumId: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            repository.isSavedAlbumCached(albumId)
        }

    suspend fun playlistsContainingTrack(
        trackUri: String,
        playlistIds: List<String>,
    ): Set<String> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        repository.playlistsContainingTrack(trackUri, playlistIds)
    }

    suspend fun isLikedTrackCached(uri: String): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            libraryRepository.isLikedTrackCached(uri)
        }

    suspend fun saveTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.saveTrack(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveTrack failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeTrack(uri: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeTrack(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeTrack failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun saveAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.saveAlbum(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "saveAlbum failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun removeAlbum(albumId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.removeAlbum(albumId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "removeAlbum failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun followPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.followPlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "followPlaylist failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    suspend fun unfollowPlaylist(playlistId: String) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.unfollowPlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "unfollowPlaylist failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    /** Fetch track metadata (art, title, duration) from the Web API for now-playing. */
    fun refreshNowPlayingFromWebApi() {
        val uri = _state.value.currentUri ?: return
        enrichNowPlayingFromWebApi(normalizeUri(uri))
    }

    suspend fun dailyMixes(): List<com.lightphone.spotify.data.SpotifyPlaylistSimple> =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                repository.dailyMixes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                android.util.Log.e("Library", "dailyMixes failed", e)
                throw Exception(mapRepositoryError(e, repository.hasPlaybackCredsWithoutLiveSession()))
            }
        }

    /** Start the MediaSessionService so OS media controls and FGS are available. */
    fun ensureServiceStarted() {
        val intent = Intent(appContext, PlaybackService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            android.util.Log.w("Playback", "startForegroundService blocked; falling back", e)
            runCatching { appContext.startService(intent) }
        } catch (e: IllegalStateException) {
            android.util.Log.w("Playback", "FGS start failed; falling back", e)
            runCatching { appContext.startService(intent) }
        }
    }

    /** Start service and attach native engine on first playback/login need. */
    fun ensureEngineReady(): Boolean {
        ensureServiceStarted()
        PlaybackEngineHolder.ensureEngineAttached(appContext, this)
        return engineReady
    }

    // --- Audio focus --------------------------------------------------------

    /** Acquire focus once; reuse the same request + listener for the session. */
    private fun ensureAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(audioFocusListener)
            .build()
            .also { focusRequest = it }
        hasAudioFocus = audioManager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasAudioFocus = false
    }

    // --- Output gain: the sleep fade and the fade between tracks ------------

    /**
     * Two things want to turn the volume down and neither knows about the other: the sleep timer
     * easing off over twenty seconds, and the fade at a track boundary. They are multiplied rather
     * than fought over, so a track change during the last seconds of a sleep timer sounds like one
     * fade instead of a fight between two.
     *
     * The gain goes to [PhonoAudioTrackSink.setVolume], which is `AudioTrack`'s own mixer gain — it
     * applies to PCM already queued in the buffer, so it is heard immediately rather than a
     * buffer-length later.
     */
    @Volatile
    private var sleepGain: Float = 1f

    @Volatile
    private var trackFadeGain: Float = 1f

    private fun applyOutputGain() {
        val gain = (sleepGain * trackFadeGain).coerceIn(0f, 1f)
        runCatching { PhonoAudioTrackSink.setVolume(gain) }
    }

    /** Back to full. Called on anything the user starts, so a fade can never be inherited. */
    private fun resetOutputGain() {
        sleepGain = 1f
        trackFadeGain = 1f
        applyOutputGain()
    }

    private val sleepOutput = object : SleepTimer.Output {
        override fun applyGain(gain: Float) {
            sleepGain = gain
            applyOutputGain()
        }

        override fun stopPlayback() {
            // Not user-initiated: this is the app deciding, and the gain is deliberately left where
            // the fade left it. Restoring it here would ramp the track back to full volume for the
            // 120ms the sink's own pause fade takes, which is an audible blip at the exact moment
            // the point was not to make one. [resetOutputGain] runs on the next play instead.
            pauseTransport(userInitiated = false)
        }

        override fun isPlaying(): Boolean = _state.value.isPlaying
    }

    /**
     * Walks [trackFadeGain] across a track boundary.
     *
     * A 100ms tick rather than the engine's own position events, which arrive once a second — a
     * fade in twelve steps is a staircase, not a fade. Running a timer during playback costs
     * nothing: the audio path already holds the CPU up, which is exactly why the *sleep* timer
     * cannot be built this way.
     */
    private var trackFadeJob: Job? = null

    private fun startTrackFadeTicker() {
        if (trackFadeJob?.isActive == true) return
        trackFadeJob = scope.launch {
            while (isActive) {
                updateTrackFadeGain()
                // Idles at once a second when no fade is set, which is the default and the case
                // that must cost nothing. It still ticks at all so turning a fade on mid-album
                // takes effect at the next boundary rather than the next track.
                delay(if (TrackFadeSettings.enabled) TRACK_FADE_TICK_MS else 1_000L)
            }
        }
    }

    private fun stopTrackFadeTicker() {
        trackFadeJob?.cancel()
        trackFadeJob = null
        if (trackFadeGain != 1f) {
            trackFadeGain = 1f
            applyOutputGain()
        }
    }

    private fun updateTrackFadeGain() {
        val half = TrackFadeSettings.halfMs
        val s = _state.value
        // Episodes are exempt. One is loaded on its own, so there is no transition to smooth, and
        // fading the first three seconds of speech only loses a sentence.
        val applies = half > 0L && s.isPlaying && !s.currentUri.isEpisodeUri()
        val wanted = if (!applies) {
            1f
        } else {
            val sincePulse = if (lastPositionAtMs > 0L) {
                (System.currentTimeMillis() - lastPositionAtMs).coerceIn(0L, 2_000L)
            } else {
                0L
            }
            TrackFade.gainAt(
                positionMs = s.positionMs + sincePulse,
                durationMs = s.durationMs,
                halfMs = half,
                hasNext = hasSomethingAfterThis(s),
            )
        }
        if (Math.abs(wanted - trackFadeGain) < 0.001f) return
        trackFadeGain = wanted
        applyOutputGain()
    }

    /**
     * Whether the tail of this track leads anywhere. Repeat counts: the same track coming round
     * again is still a transition, and the seam is where it is heard.
     */
    private fun hasSomethingAfterThis(s: PlaybackUiState): Boolean =
        s.repeatMode != RepeatMode.OFF ||
            s.queue.nextInQueue.isNotEmpty() ||
            s.queue.nextFromContext.isNotEmpty()

    /** Milliseconds until the current item ends, for the "end of track" sleep option. */
    fun endOfItemDelayMs(): Long? {
        val s = _state.value
        val speed = if (s.currentUri.isEpisodeUri()) {
            runCatching { PhonoAudioTrackSink.getPlaybackSpeed() }.getOrDefault(1f)
        } else {
            1f
        }
        return endOfItemDelayFrom(s.positionMs, s.durationMs, speed)
    }

    // --- PlayerEventListener (called from Rust runtime threads) --------------

    override fun onTrackChanged(uri: String) {
        markPlaybackPulse()
        val normalized = normalizeUri(uri)
        val cached = trackMetadata[normalized]
        // Noted for the journal, which is the only thing that wants a history — see PlayHistory.
        // Cheap enough to do inline: it is an append to today's file, and only when the track
        // actually changed. Titles come from the metadata cache, so a track whose details have not
        // arrived yet records nothing rather than a row of blanks.
        if (cached != null) {
            playHistory.record(title = cached.title, artist = cached.artists, uri = normalized)
        }
        lastPositionMs = 0L
        // Any in-flight seek belonged to the previous track.
        pendingSeekTargetMs = NO_PENDING_SEEK
        _state.update {
            it.copy(
                currentUri = normalized,
                isLoading = false,
                error = null,
                positionMs = 0L,
                title = cached?.title ?: it.title,
                artist = cached?.artists ?: it.artist,
                album = cached?.album?.takeIf { a -> a.isNotBlank() } ?: it.album,
                artUrl = cached?.artUrl ?: it.artUrl,
                albumId = cached?.albumId ?: it.albumId,
                durationMs = if (cached != null && cached.durationMs > 0) {
                    cached.durationMs
                } else {
                    0L
                },
            )
        }
        syncPlaybackModes()
        applyPlaybackSpeedFor(normalized)
        fetchMetadata(normalized)
        refreshQueue()
        onStateChanged?.invoke()
    }

    /**
     * Put the sink at the right rate for whatever just loaded.
     *
     * Applied per track rather than once when the user picks a speed, because the rate belongs to
     * podcasts and the queue can hand a song to the same sink a moment later — an episode followed
     * by music would otherwise play the music at 1.75x. Anything that is not an episode is set back
     * to 1x explicitly instead of being left alone, for exactly that reason.
     *
     * Cheap enough to call on every track change: it is a no-op inside the sink when the rate has not
     * moved, and a single `setPlaybackParams` when it has.
     */
    private fun applyPlaybackSpeedFor(uri: String?) {
        val wanted = if (uri.isEpisodeUri()) {
            PlaybackSpeed.sanitize(PodcastSettings.episodeSpeed)
        } else {
            PlaybackSpeed.NORMAL
        }
        runCatching { PhonoAudioTrackSink.setPlaybackSpeed(wanted) }
            .onFailure { e -> android.util.Log.w("Playback", "speed $wanted not applied", e) }
    }

    /**
     * Change the rate of what is playing now, for the speed control on the player.
     *
     * Refuses on anything that is not an episode so the control cannot be reached from a screen it
     * does not belong on, and returns whether the sink took it — a rate the device will not do
     * should not be shown as if it had been applied.
     */
    fun setEpisodePlaybackSpeed(speed: Float): Boolean {
        if (!state.value.currentUri.isEpisodeUri()) return false
        val clean = PlaybackSpeed.sanitize(speed)
        val ok = runCatching { PhonoAudioTrackSink.setPlaybackSpeed(clean) }.getOrDefault(false)
        if (ok) onStateChanged?.invoke()
        return ok
    }

    override fun onLoading() {
        _state.update { it.copy(isLoading = true) }
        onStateChanged?.invoke()
    }

    override fun onPlaying(positionMs: Long) {
        lastPositionMs = positionMs
        markPlaybackPulse()
        val audible = settledPositionMs(audiblePositionMs(positionMs))
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    isPlaying = true,
                    isLoading = false,
                    isBuffering = false,
                    positionMs = audible,
                    connected = true,
                    reconnecting = false,
                ),
            )
        }
        streamingPolicy.onTrackActive()
        startTrackFadeTicker()
        onStateChanged?.invoke()
    }

    override fun onPaused(positionMs: Long) {
        resetPlaybackPulse()
        // Computed before the update: settledPositionMs clears the pending-seek guard, and `update`
        // retries its lambda on contention, so a side effect belongs outside it.
        val audible = settledPositionMs(audiblePositionMs(positionMs))
        _state.update { it.copy(isPlaying = false, positionMs = audible) }
        stopTrackFadeTicker()
        onStateChanged?.invoke()
    }

    override fun onPositionChanged(positionMs: Long) {
        lastPositionMs = positionMs
        markPlaybackPulse()
        val audible = settledPositionMs(audiblePositionMs(positionMs))
        _state.update { it.copy(positionMs = audible, isBuffering = false) }
        // A seek, a pause or a speed change moves where "end of track" actually is. This only
        // re-arms the alarm when it has drifted by more than a few seconds.
        SleepTimer.refreshEndOfItem(appContext, endOfItemDelayMs())
    }

    /**
     * The position to report, given what the engine just said.
     *
     * A seek is asynchronous. For up to about a second after [seek] returns the engine is still
     * reporting the *pre-seek* position, and while the sink's buffer refills the pending-output
     * correction in [audiblePositionMs] drags that value down — to zero, if the buffer is fuller than
     * the position is long. Letting either through moves the progress bar back off where the user just
     * put it, and — the actual bug — that stale value is what the resume stores write, so scrubbing and
     * then leaving could come back at 0:00.
     *
     * So until the engine catches up, keep reporting the target. [SeekSettle] holds the decision and
     * the reasoning; this is only the plumbing around it.
     */
    private fun settledPositionMs(reportedMs: Long): Long {
        val target = pendingSeekTargetMs
        if (target == NO_PENDING_SEEK) return reportedMs
        val elapsed = SystemClock.elapsedRealtime() - pendingSeekSinceMs
        if (SeekSettle.hasLanded(reportedMs, target, elapsed) || SeekSettle.isExpired(elapsed)) {
            pendingSeekTargetMs = NO_PENDING_SEEK
            return reportedMs
        }
        return target
    }

    override fun onDurationMs(durationMs: Long) {
        if (durationMs <= 0L) return
        _state.update { state ->
            if (state.durationMs == durationMs) state
            else state.copy(durationMs = durationMs)
        }
        onStateChanged?.invoke()
    }

    /** Subtract AudioTrack HAL pending from Spotify Path C stream position only. */
    private fun audiblePositionMs(streamPositionMs: Long): Long {
        if (backendChoice != BackendChoice.SPOTIFY || !BuildConfig.USE_AUDIOTRACK_SINK) {
            return streamPositionMs
        }
        val delayMs = runCatching { PhonoAudioTrackSink.getOutputDelayMs() }.getOrDefault(0)
        return (streamPositionMs - delayMs).coerceAtLeast(0L)
    }

    override fun onBuffering(stalled: Boolean) {
        _state.update { it.copy(isBuffering = stalled, isLoading = stalled) }
        onStateChanged?.invoke()
    }

    override fun onEndOfTrack() {
        resetPlaybackPulse()
        pendingSeekTargetMs = NO_PENDING_SEEK
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
        stopTrackFadeTicker()
        // The queue is out. Rust only reports this when there is nothing to advance to, so this is
        // playback ending — and a sleep timer with nothing left to stop must not stay armed.
        SleepTimer.onPlaybackStopped(appContext)
        abandonFocus()
        refreshQueue()
        onStateChanged?.invoke()
    }

    override fun onUnavailable(uri: String) {
        // Rust auto-advances the queue; avoid sticky error state.
    }

    override fun onConnectionLost() {
        _state.update {
            recomputeStatusMessage(
                it.copy(
                    connected = false,
                    reconnecting = true,
                    isPlaying = false,
                    isBuffering = false,
                ),
            )
        }
        onStateChanged?.invoke()
    }

    override fun onConnectionRestored() {
        syncConnectedFromEngine()
        refreshQueue()
        onSessionRestored?.invoke()
        onStateChanged?.invoke()
    }

    override fun onError(message: String) {
        if (!_state.value.networkOnline && isOfflineNoiseError(message)) {
            android.util.Log.w("Playback", "suppressing offline reconnect noise: $message")
            return
        }
        val mapped = when {
            !_state.value.networkOnline && (
                message.contains("not available offline", ignoreCase = true) ||
                    message.contains("not logged in", ignoreCase = true) ||
                    message.contains("network", ignoreCase = true)
                ) -> "Not available offline."
            else -> message
        }
        _state.update { it.copy(error = mapped, isPlaying = false) }
        onStateChanged?.invoke()
    }

    private fun isOfflineNoiseError(message: String): Boolean =
        message.contains("Playback reconnect failed", ignoreCase = true) ||
            (message.contains("reconnect", ignoreCase = true) &&
                message.contains("failed", ignoreCase = true))

    override fun onQueueChanged() {
        refreshQueue()
    }

    private fun fetchMetadata(uri: String) {
        val normalized = normalizeUri(uri)
        val cached = trackMetadata[normalized]
        if (cached != null) {
            applyTrackMetadata(cached)
            if (cached.artUrl != null) return
        }
        enrichNowPlayingFromWebApi(normalized)
    }

    private fun enrichNowPlayingFromWebApi(uri: String) {
        scope.launch {
            runCatching { repository.trackMetadataForUri(uri) }
                .onSuccess { meta ->
                    if (meta == null) return@onSuccess
                    trackMetadata[uri] = meta
                    if (normalizeUri(_state.value.currentUri.orEmpty()) == uri) {
                        applyTrackMetadata(meta)
                    }
                }
                .onFailure { e ->
                    android.util.Log.w("Playback", "Web API now-playing enrich failed", e)
                }
        }
    }

    private fun applyTrackMetadata(meta: TrackMetadata) {
        _state.update {
            it.copy(
                title = meta.title,
                artist = meta.artists,
                album = meta.album.takeIf { a -> a.isNotBlank() } ?: it.album,
                artUrl = meta.artUrl,
                albumId = meta.albumId,
                durationMs = if (meta.durationMs > 0) meta.durationMs else it.durationMs,
            )
        }
        onStateChanged?.invoke()
    }

    private fun syncPlaybackModes() {
        if (!engineReady) return
        _state.update {
            it.copy(
                shuffleEnabled = requireBackend().getShuffle(),
                repeatMode = requireBackend().getRepeatMode(),
            )
        }
    }

    private fun normalizeUri(uri: String): String = uri.substringBefore('?').trim()

    /**
     * Metadata for a completed download, read off the downloads table.
     *
     * This wrapped the suspend DAO call in `runBlocking`, justified by `trackMetadataForUri` being
     * a blocking call already off the main thread. It wasn't: the chain up to it was reachable from
     * `viewModelScope`. `localMetadata` is a suspend function type now, so this is a plain suspend
     * read and there is nothing left to block on.
     */
    private suspend fun downloadedMetadata(uri: String): TrackMetadata? = runCatching {
        database.downloadedTrackDao().getByUri(uri)
            ?.takeIf { it.state == DownloadStates.COMPLETED && it.duration_ms > 0L }
            ?.toMetadata()
    }.getOrNull()

    /**
     * Whether the phone actually has internet, not merely a network attached.
     *
     * This asked for `NET_CAPABILITY_INTERNET`, which is a *claim* the transport makes about itself:
     * it is set on a captive-portal Wi-Fi and on a cellular link that is registered but carrying no
     * data. `NET_CAPABILITY_VALIDATED` is the platform's finding that traffic actually reached the
     * internet, which is the question being asked.
     *
     * This one predicate is load-bearing for the whole offline path. When it wrongly says "online",
     * the engine's `local_audio_only()` is false, so it skips the downloaded-file fast path, does not
     * pin-gate queue advancement, never hands off to local audio — and instead tries to rebuild the
     * Spotify session, which on a dead network means a long blocking access-point connect while the
     * user stares at a spinner over a file that is sitting on disk. That was the "downloaded music
     * doesn't play with no internet" bug.
     */
    private fun isNetworkOnline(): Boolean = NetworkStatus.isOnline(appContext)

    private fun recomputeStatusMessage(state: PlaybackUiState): PlaybackUiState {
        val message = when {
            state.sessionExpired -> "Session expired — sign out and sign in again."
            state.reconnecting -> "Reconnecting…"
            !state.networkOnline -> "No connection."
            else -> null
        }
        return state.copy(statusMessage = message)
    }

    private fun mapPlayFailure(e: Throwable): String {
        val raw = e.message.orEmpty()
        if (!_state.value.networkOnline) {
            if (raw.contains("not available offline", ignoreCase = true) ||
                raw.contains("not logged in", ignoreCase = true) ||
                raw.contains("network", ignoreCase = true)
            ) {
                return "Not available offline."
            }
        }
        return mapSpotifyError(e)
    }

    private fun mapSpotifyError(e: Throwable): String {
        val httpMessage = e.message?.let { msg ->
            when {
                msg.startsWith("HTTP 429") -> {
                    val retrySec = Regex("retry after (\\d+)s").find(msg)?.groupValues?.get(1)
                    if (retrySec != null) {
                        "Spotify is busy — try again in ${retrySec}s."
                    } else {
                        "Spotify is busy — wait a moment and try again."
                    }
                }
                msg.startsWith("HTTP 401") || msg.startsWith("HTTP 403") ->
                    "Session expired — sign out and sign in again."
                msg.startsWith("HTTP") && !_state.value.networkOnline -> "No connection."
                msg.startsWith("HTTP") -> "Can't reach Spotify right now. Try again."
                else -> null
            }
        }
        val message = when (e) {
            // Keep the real Auth message on login (e.g. state mismatch / no pending).
            // "Session expired" is wrong for first-time OAuth failures.
            is SpotifyException.Auth -> e.message?.takeIf { it.isNotBlank() }
                ?: "Sign-in failed. Try again."
            is SpotifyException.Network ->
                when {
                    e.message?.contains("not available offline", ignoreCase = true) == true ->
                        "Not available offline."
                    !_state.value.networkOnline -> "No connection."
                    else -> "Can't reach Spotify right now. Try again."
                }
            is SpotifyException.NotLoggedIn ->
                if (!_state.value.networkOnline) "Not available offline." else "Not signed in."
            else -> httpMessage ?: (e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong. Try again.")
        }
        if (e is SpotifyException.Auth) {
            // Only mark session-expired for post-login auth failures, not OAuth bootstrap.
            val bootstrap = e.message?.contains("pending login", ignoreCase = true) == true ||
                e.message?.contains("state mismatch", ignoreCase = true) == true ||
                e.message?.contains("CSRF", ignoreCase = true) == true
            // A token refresh that fails with no connection surfaces here as an Auth error, but the
            // session is fine — the network is not. Latching sessionExpired on that told the user to
            // sign in again on a train, and the banner then hid "Device offline", which is the thing
            // that actually explains why nothing loads. Offline auth failures are left to the
            // network state, and the flag is set only when we could reach Spotify and it said no.
            if (!bootstrap && _state.value.networkOnline) {
                _state.update { recomputeStatusMessage(it.copy(sessionExpired = true)) }
            }
        }
        return message
    }

    companion object {
        /** Sentinel for "no seek in flight"; a real target is always >= 0. */
        private const val NO_PENDING_SEEK = -1L

        private const val STALL_POLL_MS = 2000L
        private const val STALL_BUFFERING_MS = 8000L

        /**
         * How long audio must be dry before falling back to a downloaded copy *without* having been
         * told the connection is gone. Comfortably longer than [STALL_BUFFERING_MS] so a normal
         * rebuffer on slow data is left alone, short enough that a dead zone is not a minute of
         * silence.
         */
        private const val STALL_LOCAL_HANDOFF_MS = 15000L

        /**
         * How long `isLoading` may stay true with no player event before it is given up on. Long
         * enough to cover a slow first load over bad data, short enough that a no-op resume does not
         * leave a permanently dead-looking button.
         */
        /**
          * A load that has produced no audio for this long is given up on.
          *
          * Was 20s, which on a train is twenty seconds of silence in the middle of an album that is
          * sitting on the phone. The hand-off it triggers prefers a downloaded copy and does nothing
          * at all when there is none, so shortening it cannot interrupt a slow load that would have
          * succeeded — it can only reach for a file that is already there.
          */
         private const val LOADING_STUCK_MS = 9000L
        private const val NETWORK_HANDOFF_GRACE_MS = 3000L
        private const val RECONNECT_DEBOUNCE_MS = 6000L
        private const val AUDIO_ROUTE_DEBOUNCE_MS = 400L
        private const val TRANSPORT_CONFIRM_SAMPLES = 2
        private const val LOGOUT_JOIN_TIMEOUT_MS = 3_000L

        /** Coalesce window for rapid transport taps while reconnecting so a burst
         *  of skips triggers a single native load/rebuild for the final target. */
        private const val TRANSPORT_COALESCE_MS = 300L

        /** Fade resolution. Twelve steps a second is smooth and costs nothing while audio runs. */
        private const val TRACK_FADE_TICK_MS = 100L

        @Volatile
        private var instance: PlaybackController? = null

        fun get(context: Context): PlaybackController {
            return instance ?: synchronized(this) {
                instance ?: run {
                    // Null only in the window logout opens: AppViewModel.logout clears the stored
                    // choice, MainActivity recreates, and setContent's ensureController lands here
                    // before anything has re-pinned it — a guaranteed crash on every in-process
                    // logout (light-reports#16). Upstream had a picker to recreate into; LightPhono
                    // has one backend, so re-pin it exactly the way App.onCreate would on the next
                    // launch instead of erroring.
                    val backendPrefs = com.lightphone.spotify.data.backend.BackendPreferences(context)
                    val choice = backendPrefs.choice() ?: run {
                        backendPrefs.ensureSpotify()
                        com.lightphone.spotify.data.backend.BackendChoice.SPOTIFY
                    }
                    PlaybackController(
                        appContext = context.applicationContext,
                        backendChoice = choice,
                        webApiAuth = PlaybackEngineHolder.webApiAuth(context),
                    )
                }.also { instance = it }
            }
        }

        /** Tear down the singleton after logout so a new backend pick can rebuild. */
        fun clearInstance() {
            synchronized(this) {
                val old = instance ?: return
                instance = null
                // Unregisters the becoming-noisy receiver, the network callback and the
                // audio-device callback as well as cancelling the scope. Before this, every
                // logout left three live system callbacks pointing at a dead controller and the
                // next login registered three more.
                old.release()
                runCatching {
                    old.appContext.stopService(Intent(old.appContext, PlaybackService::class.java))
                }
                PlaybackEngineHolder.resetForBackendSwitch()
            }
        }
    }

    /** Build the concrete [PlaybackBackend]. Spotify-only since the TIDAL strip. */
    internal fun createBackend(): PlaybackBackend =
        com.lightphone.spotify.playback.backend.LibrespotPlaybackBackend(
            PlaybackEngineHolder.createEngine(appContext),
        )
}

data class SettingsSnapshot(
    val streamingQuality: StreamingQuality,
    val gaplessEnabled: Boolean,
    val normalizationEnabled: Boolean,
    val normalizationType: NormalizationType,
    val proxy: String?,
)
