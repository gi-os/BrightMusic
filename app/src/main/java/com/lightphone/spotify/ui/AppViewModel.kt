package com.lightphone.spotify.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.lazy.LazyListState
import com.lightphone.spotify.App
import com.lightphone.spotify.data.LikedFilter
import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.data.SearchFilter
import com.lightphone.spotify.data.SearchResults
import com.lightphone.spotify.data.SearchResultItem
import com.lightphone.spotify.data.SpotifyAlbumDetail
import com.lightphone.spotify.data.SpotifyArtistDetail
import com.lightphone.spotify.data.SpotifyTrack
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.isEpisodeUri
import com.lightphone.spotify.data.SpotifyPlaylistDetail
import com.lightphone.spotify.data.local.LikedTrackEntity
import com.lightphone.spotify.data.local.PlaylistEntity
import com.lightphone.spotify.data.local.SavedAlbumEntity
import com.lightphone.spotify.data.session.SessionEvent
import com.lightphone.spotify.data.backend.BackendCapabilities
import com.lightphone.spotify.data.backend.BackendChoice
import com.lightphone.spotify.data.backend.CollectionKind
import com.lightphone.spotify.data.backend.collectionUri
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ffi.NormalizationType
import com.lightphone.spotify.ffi.RepeatMode
import com.lightphone.spotify.ffi.StreamingQuality
import com.lightphone.spotify.ui.components.PhonoContextMenuItem
import com.lightphone.spotify.playback.PlaybackController
import com.lightphone.spotify.playback.PlaybackResume
import com.lightphone.spotify.playback.connect.ConnectAliasPreferences
import com.lightphone.spotify.playback.connect.ConnectAliases
import com.lightphone.spotify.playback.PlaybackUiState
import com.lightphone.spotify.playback.SettingsSnapshot
import com.lightphone.spotify.data.local.PhonoDatabase
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.audio.AudioOutputs
import com.lightphone.spotify.audio.BluetoothConnector
import com.lightphone.spotify.audio.PhonoAudioTrackSink
import com.lightphone.spotify.data.webapi.LibraryPage
import com.lightphone.spotify.data.webapi.SpotifyDevice
import com.lightphone.spotify.playback.connect.ConnectUiState
import com.lightphone.spotify.data.mapRepositoryError
import com.lightphone.spotify.data.webapi.SpotifyEpisode
import com.lightphone.spotify.data.webapi.SpotifyShow
import com.lightphone.spotify.podcast.EpisodePaging
import com.lightphone.spotify.podcast.PodcastAutoDownload
import com.lightphone.spotify.podcast.EpisodeResume
import com.lightphone.spotify.podcast.PodcastPreferences
import com.lightphone.spotify.podcast.PodcastRetention
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.playback.SleepChoice
import com.lightphone.spotify.playback.SleepTimer
import com.lightphone.spotify.playback.TrackFade
import com.lightphone.spotify.playback.TrackFadePreferences
import com.lightphone.spotify.playback.TrackFadeSettings
import com.lightphone.spotify.radio.DefaultStations
import com.lightphone.spotify.radio.RadioBrowserApi
import com.lightphone.spotify.radio.RadioController
import com.lightphone.spotify.radio.RadioPreferences
import com.lightphone.spotify.radio.RadioStation
import com.lightphone.spotify.radio.RadioUiState
import com.lightphone.spotify.playback.PlaybackEngineHolder
import com.lightphone.spotify.playback.connect.StoredCredentials
import com.lightphone.spotify.playback.connect.ZeroconfClaim
import com.lightphone.spotify.playback.connect.ZeroconfDiscovery
import com.lightphone.spotify.playback.connect.RemotePlayback
import com.lightphone.spotify.ui.light.ArtworkPreferences
import com.lightphone.spotify.ui.light.PinnedItems
import com.lightphone.spotify.ui.light.PinnedPreferences
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.ThemePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

data class AlbumDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val album: SpotifyAlbumDetail? = null,
    val isSaved: Boolean = false,
    val isSavedConfirmed: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ArtistDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val artist: SpotifyArtistDetail? = null,
    val topTracks: List<SpotifyTrack> = emptyList(),
    val albums: List<com.lightphone.spotify.data.SpotifyAlbumSimple> = emptyList(),
    val error: String? = null,
)

data class SearchUiState(
    val query: String = "",
    val resultsQuery: String? = null,
    val results: SearchResults? = null,
    val initialLoading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val refreshError: String? = null,
    val filter: SearchFilter = SearchFilter.All,
) {
    val displayResults: SearchResults?
        get() = results?.takeIf { resultsQuery == query }

    val hasDisplayableResults: Boolean
        get() = displayResults?.let { !it.isEmpty() } == true

    val isEmpty: Boolean
        get() = resultsQuery == query &&
            results != null &&
            results.isEmpty() &&
            !initialLoading &&
            !refreshing &&
            error == null &&
            refreshError == null
}

data class PlayingExtrasState(
    val isTrackSaved: Boolean = false,
    val savePending: Boolean = false,
    val saveError: String? = null,
)

/**
 * One show's episode list, as far down the feed as it has been scrolled.
 *
 * [total] is what the feed says it holds, not what has been fetched — it drives the scrollbar, tells
 * the pager when to stop, and is what makes reading a feed oldest-first possible at all. See
 * [com.lightphone.spotify.podcast.EpisodePaging].
 */
data class ShowEpisodesUiState(
    val episodes: List<SpotifyEpisode> = emptyList(),
    val total: Int = 0,
    val oldestFirst: Boolean = false,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = total > 0 && episodes.size < total
}

/** Saved shows and the episodes fetched for each. Online-only; see PodcastsScreen. */
/**
 * The account's saved episodes, from `/me/episodes`.
 *
 * Kept separate from [PodcastsUiState] rather than folded into it because it is read on entry to its
 * own screen and nothing else depends on it — an episode saved here is not a show followed there.
 */
data class SavedEpisodesUiState(
    val episodes: List<SpotifyEpisode> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

data class PodcastsUiState(
    val shows: List<SpotifyShow> = emptyList(),
    /** Saved shows the account has, for paging past the first fifty. */
    val showsTotal: Int = 0,
    val showsAppending: Boolean = false,
    val episodesByShow: Map<String, ShowEpisodesUiState> = emptyMap(),
    /**
     * Episode ids ticked for download. Null rather than empty when not selecting, so "selection mode
     * with nothing ticked yet" and "not selecting" are different states — the first still needs its
     * checkboxes and its Cancel.
     */
    val selectedEpisodeIds: Set<String>? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    val selecting: Boolean get() = selectedEpisodeIds != null

    val showsHasMore: Boolean get() = showsTotal > 0 && shows.size < showsTotal
}

data class SettingsUiState(
    val streamingQuality: StreamingQuality = StreamingQuality.NORMAL,
    val downloadQuality: StreamingQuality = StreamingQuality.HIGH,
    val gaplessEnabled: Boolean = true,
    val normalizationEnabled: Boolean = false,
    val normalizationType: NormalizationType = NormalizationType.AUTO,
    val proxy: String = "",
    val showAdvanced: Boolean = false,
    val darkTheme: Boolean = true,
)

data class PlaylistDetailTrackRow(
    val track: SpotifyTrack,
    val addedAt: String?,
    val uri: String,
)

data class PlaylistDetailState(
    val loading: Boolean = false,
    val requestedId: String? = null,
    val detail: SpotifyPlaylistDetail? = null,
    val tracks: List<PlaylistDetailTrackRow> = emptyList(),
    val snapshotId: String? = null,
    val isEditable: Boolean = false,
    val isInLibrary: Boolean = false,
    val editMode: Boolean = false,
    val mutating: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val mutationError: String? = null,
)

/** The user's saved stations, plus whether they have been read off disk yet. */
data class RadioLibraryUiState(
    val stations: List<RadioStation> = emptyList(),
    val loaded: Boolean = false,
)

data class RadioSearchUiState(
    val query: String = "",
    val loading: Boolean = false,
    val results: List<RadioStation> = emptyList(),
    /** True once a query has come back, which is what separates "no results" from "type something". */
    val searched: Boolean = false,
)

data class CreatePlaylistState(
    val creating: Boolean = false,
    val error: String? = null,
)

data class PlaylistPickerState(
    val trackUri: String = "",
    val loading: Boolean = false,
    val adding: Boolean = false,
    val playlists: List<PlaylistEntity> = emptyList(),
    val containingPlaylistIds: Set<String> = emptySet(),
    val selectedPlaylistIds: Set<String> = emptySet(),
    /** Whether the track is currently in Liked Songs (server/local truth at load). */
    val isInLikedSongs: Boolean = false,
    /** Checkbox state for the Liked Songs row (may differ from [isInLikedSongs] before apply). */
    val likedSongsSelected: Boolean = false,
    val error: String? = null,
    val statusMessage: String? = null,
) {
    val hasPendingChanges: Boolean
        get() = likedSongsSelected != isInLikedSongs || selectedPlaylistIds.isNotEmpty()
}

enum class ContextMenuAction {
    CopyLink,
    AddToPlaylists,
    RemoveFromLibrary,
    DeletePlaylist,
    Download,
    RemoveDownload,

    /**
     * Pin a playlist or show to the top of its list. Distinct from [Download], which is Spotify's own
     * offline "pin" — this one only reorders, and the two are independent.
     */
    TogglePin,

    /** Turn a show's automatic download of new episodes on or off. */
    ToggleAutoDownload,
}

/** Offline pin state for an album/playlist header icon. */
enum class CollectionDownloadUi {
    None,
    Downloading,
    Complete,
}

sealed interface ContextMenuTarget {
    data class Track(val uri: String, val id: String) : ContextMenuTarget
    data class Album(val albumId: String, val uri: String) : ContextMenuTarget
    data class Playlist(val playlistId: String, val uri: String, val ownerId: String) : ContextMenuTarget
    data class Show(val showId: String, val uri: String) : ContextMenuTarget
}

/** A collection whose offline copy is about to be removed. [name] is for the prompt. */
data class RemoveDownloadConfirm(
    val collectionUri: String,
    val name: String,
)

data class DeletePlaylistConfirm(
    val playlistId: String,
    val name: String,
)

data class ContextMenuUiState(
    val target: ContextMenuTarget? = null,
    val showCopied: Boolean = false,
    val deleteConfirm: DeletePlaylistConfirm? = null,
    /**
     * Set while asking before wiping a collection's offline copy. Deleting downloads is slow to
     * undo — every track has to come back over the network — so it gets the same confirmation
     * deleting a playlist does.
     */
    val removeDownloadConfirm: RemoveDownloadConfirm? = null,
    val navigateToPlaylistPickerUri: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val controller: PlaybackController = (app as App).ensureController()
    private val themePreferences = ThemePreferences(app)
    private val artworkPreferences = ArtworkPreferences(app)
    private val trackFadePreferences = TrackFadePreferences(app)

    /** Active backend. Spotify-only, kept as a seam for upstream merges. */
    val backendChoice = controller.backendChoice

    /**
     * NTS Radio, running beside the Spotify engine. Starting radio pauses Spotify (the controller does
     * that itself); starting Spotify stops radio, which is enforced in [playRadio]'s counterparts below.
     */
    private val radioController = RadioController(
        context = app,
        scope = viewModelScope,
        onStartRadio = { controller.pause() },
    )

    val radio: StateFlow<RadioUiState> = radioController.state

    private val radioBrowser = RadioBrowserApi()
    private val radioPreferences = RadioPreferences(app)

    private val _radioLibrary = MutableStateFlow(RadioLibraryUiState())
    val radioLibrary: StateFlow<RadioLibraryUiState> = _radioLibrary.asStateFlow()

    private val _radioSearch = MutableStateFlow(RadioSearchUiState())
    val radioSearch: StateFlow<RadioSearchUiState> = _radioSearch.asStateFlow()

    /**
     * Load saved stations, seeding the New York set on a first run.
     *
     * Called from the Radio tab rather than the constructor, so an install that never opens Radio never
     * touches the file. Seeding is recorded separately from the list itself: writing the seed and then
     * checking "is the list empty" would put every removed station back the moment the user cleared them
     * all.
     */
    fun loadRadioLibrary() {
        if (_radioLibrary.value.loaded) return
        val saved = if (radioPreferences.seeded()) {
            radioPreferences.favorites()
        } else {
            DefaultStations.NEW_YORK.also {
                radioPreferences.setFavorites(it)
                radioPreferences.markSeeded()
            }
        }
        _radioLibrary.value = RadioLibraryUiState(stations = saved, loaded = true)
    }

    fun addRadioStation(station: RadioStation) {
        val current = _radioLibrary.value.stations
        if (current.any { it.id == station.id }) return
        val updated = current + station
        radioPreferences.setFavorites(updated)
        _radioLibrary.value = _radioLibrary.value.copy(stations = updated)
    }

    fun removeRadioStation(stationId: String) {
        val updated = _radioLibrary.value.stations.filterNot { it.id == stationId }
        radioPreferences.setFavorites(updated)
        _radioLibrary.value = _radioLibrary.value.copy(stations = updated)
        // Removing the station that is playing leaves it playing on purpose: the user asked to take it
        // out of their list, not to stop the audio.
    }

    fun toggleRadioStation(station: RadioStation) {
        if (_radioLibrary.value.stations.any { it.id == station.id }) {
            removeRadioStation(station.id)
        } else {
            addRadioStation(station)
        }
    }

    /** Search the directory. Blank queries clear rather than search for everything. */
    fun searchRadioStations(query: String) {
        val trimmed = query.trim()
        _radioSearch.value = RadioSearchUiState(query = trimmed, loading = trimmed.isNotBlank())
        if (trimmed.isBlank()) return
        radioSearchJob?.cancel()
        radioSearchJob = viewModelScope.launch {
            val results = runCatching { radioBrowser.search(trimmed) }.getOrDefault(emptyList())
            if (_radioSearch.value.query != trimmed) return@launch
            _radioSearch.value = RadioSearchUiState(
                query = trimmed,
                loading = false,
                results = results.map { it.toStation() },
                // An empty list after a successful call and after a failed one look identical to the
                // user, so both read as "nothing found" rather than pretending to know which it was.
                searched = true,
            )
        }
    }

    private var radioSearchJob: Job? = null

    private val podcastPreferences = PodcastPreferences(app)
    private val connectAliasPreferences = ConnectAliasPreferences(app)

    private val _podcasts = MutableStateFlow(PodcastsUiState())
    val podcasts: StateFlow<PodcastsUiState> = _podcasts.asStateFlow()

    private val _savedEpisodes = MutableStateFlow(SavedEpisodesUiState())
    val savedEpisodes: StateFlow<SavedEpisodesUiState> = _savedEpisodes.asStateFlow()

    /**
     * Load the account's saved episodes. [force] is for coming back after saving or unsaving one.
     *
     * One page. Saved episodes are a handful of things you meant to get back to rather than a library
     * to page through, and a runway here would be scaffolding for a list that is almost never long.
     */
    fun loadSavedEpisodes(force: Boolean = false) {
        val current = _savedEpisodes.value
        if (current.loading || (current.loaded && !force)) return
        _savedEpisodes.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            val result = runCatching { controller.savedEpisodesPage(offset = 0) }
            _savedEpisodes.value = result.fold(
                onSuccess = { page ->
                    SavedEpisodesUiState(episodes = page.items, loading = false, loaded = true)
                },
                onFailure = { e ->
                    SavedEpisodesUiState(
                        episodes = current.episodes,
                        loading = false,
                        loaded = current.loaded,
                        error = e.message ?: "Could not load saved episodes",
                    )
                },
            )
        }
    }

    /** Unsave an episode from the saved-episodes list, dropping the row straight away. */
    fun removeSavedEpisode(uri: String) {
        _savedEpisodes.value = _savedEpisodes.value.copy(
            episodes = _savedEpisodes.value.episodes.filterNot { it.uri == uri },
        )
        viewModelScope.launch { runCatching { controller.removeTrack(uri) } }
    }

    fun loadSavedShows() {
        if (_podcasts.value.shows.isNotEmpty() || _podcasts.value.loading) return
        _podcasts.value = _podcasts.value.copy(loading = true, error = null)
        fetchShowsPage(offset = 0)
    }

    /**
     * Fetch the next page of saved shows as the list is scrolled near its loaded edge.
     *
     * `/me/shows` caps a page at fifty, and the screen used to render exactly that one page — a
     * fifty-first subscription simply did not exist as far as the app was concerned.
     */
    fun ensureShowsBufferAhead(lastVisibleIndex: Int) {
        val state = _podcasts.value
        if (state.loading || state.showsAppending || !state.showsHasMore) return
        if (lastVisibleIndex < 0) return
        _podcasts.value = state.copy(showsAppending = true)
        fetchShowsPage(offset = state.shows.size)
    }

    private fun fetchShowsPage(offset: Int) {
        viewModelScope.launch {
            runCatching { controller.savedShowsPage(offset) }
                .onSuccess { page ->
                    val fetched = page.items.mapNotNull { it.show }
                    val existing = if (offset == 0) emptyList() else _podcasts.value.shows
                    // Ids already held are dropped: following a show mid-scroll shifts every offset
                    // by one, and a repeat is a duplicate LazyColumn key, which crashes rather than
                    // merely looking odd.
                    val seen = existing.mapTo(HashSet()) { it.id }
                    val merged = existing + fetched.filter { seen.add(it.id) }
                    _podcasts.value = _podcasts.value.copy(
                        shows = merged,
                        // Same reasoning as the episode pager: a page that added nothing is the end
                        // of the list, and believing `total` over that would ask for the same offset
                        // on every scroll.
                        showsTotal = if (merged.size == existing.size) merged.size else page.total,
                        loading = false,
                        showsAppending = false,
                    )
                }
                .onFailure { e ->
                    _podcasts.value = _podcasts.value.copy(
                        loading = false,
                        showsAppending = false,
                        error = mapRepositoryError(e),
                    )
                }
        }
    }

    /** First page of a show's episodes, in whatever order [PodcastSettings.episodesOldestFirst] says. */
    fun loadShowEpisodes(showId: String) {
        val existing = _podcasts.value.episodesByShow[showId]
        if (existing != null && (existing.episodes.isNotEmpty() || existing.loading)) return
        fetchEpisodePage(showId, initial = true)
    }

    /** Fetch further into the feed as the episode list is scrolled near its loaded edge. */
    fun ensureEpisodeBufferAhead(showId: String, lastVisibleIndex: Int) {
        val state = _podcasts.value.episodesByShow[showId] ?: return
        if (state.loading || state.appending || !state.hasMore || lastVisibleIndex < 0) return
        fetchEpisodePage(showId, initial = false)
    }

    /**
     * Flip every episode list between newest-first and oldest-first.
     *
     * The loaded episodes are dropped rather than reversed in place: only part of the feed is on the
     * phone, so reversing would show the newest fifty backwards and call it the beginning of the show.
     * The refetch reads the other end of the feed properly. Totals survive — the feed did not change
     * length, and keeping them saves the request that would otherwise be needed to learn it again.
     */
    fun toggleEpisodeSort(showId: String) {
        val oldestFirst = !PodcastSettings.episodesOldestFirst
        PodcastSettings.setEpisodesOldestFirst(podcastPreferences, oldestFirst)
        _podcasts.value = _podcasts.value.copy(
            episodesByShow = _podcasts.value.episodesByShow.mapValues { (_, state) ->
                // The flags are cleared as well as the episodes: a page in flight for the old order
                // is about to be discarded on arrival, and leaving `loading` set on its behalf would
                // block the refetch below and strand the screen on an empty list.
                state.copy(
                    episodes = emptyList(),
                    oldestFirst = oldestFirst,
                    error = null,
                    loading = false,
                    appending = false,
                )
            },
        )
        fetchEpisodePage(showId, initial = true)
    }

    private fun fetchEpisodePage(showId: String, initial: Boolean) {
        val current = _podcasts.value.episodesByShow[showId]
            ?: ShowEpisodesUiState(oldestFirst = PodcastSettings.episodesOldestFirst)
        if (current.loading || current.appending) return
        val oldestFirst = current.oldestFirst
        updateEpisodes(showId) { it.copy(loading = initial, appending = !initial, error = null) }
        viewModelScope.launch {
            // The show itself is fetched too when it is not already in the saved list, so opening an
            // episode list always has a title and cover even for a show you have not followed.
            if (_podcasts.value.shows.none { it.id == showId }) {
                runCatching { controller.show(showId) }.getOrNull()?.let { fetched ->
                    _podcasts.value = _podcasts.value.copy(shows = _podcasts.value.shows + fetched)
                }
            }
            runCatching {
                var total = current.total
                if (EpisodePaging.needsTotal(total, oldestFirst)) {
                    // One item, purely to read the feed's length out of the envelope — see
                    // EpisodePaging.PROBE_LIMIT.
                    total = controller.showEpisodesPage(showId, 0, EpisodePaging.PROBE_LIMIT).total
                }
                val request = EpisodePaging.nextRequest(current.episodes.size, total, oldestFirst)
                    ?: return@runCatching LibraryPage(emptyList<SpotifyEpisode>(), total, 0)
                controller.showEpisodesPage(showId, request.offset, request.limit)
            }
                .onSuccess { page ->
                    updateEpisodes(showId) { state ->
                        // A sort flip while this page was in flight makes it the wrong half of the
                        // feed; the flip already queued its own first page, so drop this one.
                        if (state.oldestFirst != oldestFirst) return@updateEpisodes state
                        val merged = EpisodePaging.merge(
                            loaded = state.episodes,
                            page = page.items,
                            oldestFirst = state.oldestFirst,
                        ) { it.id }
                        state.copy(
                            episodes = merged,
                            // A page that added nothing means the end of the feed however long
                            // `total` says it is. Without this the next offset is the same offset,
                            // and scrolling to the bottom refetches it forever.
                            total = if (merged.size == state.episodes.size) {
                                merged.size
                            } else {
                                maxOf(page.total, state.total)
                            },
                            loading = false,
                            appending = false,
                        )
                    }
                }
                .onFailure { e ->
                    updateEpisodes(showId) {
                        it.copy(loading = false, appending = false, error = mapRepositoryError(e))
                    }
                }
        }
    }

    private fun updateEpisodes(showId: String, block: (ShowEpisodesUiState) -> ShowEpisodesUiState) {
        val byShow = _podcasts.value.episodesByShow
        val current = byShow[showId]
            ?: ShowEpisodesUiState(oldestFirst = PodcastSettings.episodesOldestFirst)
        _podcasts.value = _podcasts.value.copy(episodesByShow = byShow + (showId to block(current)))
    }

    /**
     * Start ticking episodes to download together.
     *
     * A mode rather than a per-row download button: the row is already a play target, and picking six
     * episodes for a flight one long-press at a time is six chances to start playing one instead.
     */
    fun startEpisodeSelection() {
        if (!downloadsSupported || _podcasts.value.selecting) return
        _podcasts.value = _podcasts.value.copy(selectedEpisodeIds = emptySet())
    }

    fun cancelEpisodeSelection() {
        if (!_podcasts.value.selecting) return
        _podcasts.value = _podcasts.value.copy(selectedEpisodeIds = null)
    }

    fun toggleEpisodeSelected(episodeId: String) {
        val selected = _podcasts.value.selectedEpisodeIds ?: return
        _podcasts.value = _podcasts.value.copy(
            selectedEpisodeIds = if (episodeId in selected) selected - episodeId else selected + episodeId,
        )
    }

    /**
     * Download everything ticked, as one collection write.
     *
     * One `downloadCollection` call rather than one per episode so the show's membership rows are
     * written once, and so the whole batch either goes in or does not.
     */
    fun downloadSelectedEpisodes(showId: String) {
        val selected = _podcasts.value.selectedEpisodeIds.orEmpty()
        _podcasts.value = _podcasts.value.copy(selectedEpisodeIds = null)
        if (!downloadsSupported || selected.isEmpty()) return
        val episodes = _podcasts.value.episodesByShow[showId]?.episodes.orEmpty()
            .filter { it.id in selected && it.isStreamable }
        queueEpisodeDownloads(showId, episodes)
    }

    /**
     * Turn auto-download on or off for a show.
     *
     * Turning it on checks immediately rather than waiting for the daily alarm — the point of
     * enabling it is usually that you want something to listen to now.
     */
    fun toggleShowAutoDownload(showId: String) {
        val on = PodcastSettings.toggleAutoDownload(podcastPreferences, showId)
        if (on) PodcastAutoDownload.checkNow(getApplication(), force = true)
    }

    /** Where playback of this episode got to, for the "N min left" line and for resuming. */
    fun episodeResumeMs(episodeUri: String): Long = podcastPreferences.resumePosition(episodeUri)

    /**
     * Step the episode rate to the next value in the cycle.
     *
     * The preference is written whether or not the sink accepted the rate, so the choice survives a
     * device that refused it this once — the next load applies it again. The UI reads
     * [PodcastSettings.episodeSpeed], which is Compose state, so the button relabels itself.
     */
    private val libraryAutoPrefs =
        com.lightphone.spotify.playback.download.LibraryAutoDownloadPreferences(app)

    /**
     * Turning the automatic download on runs a check immediately rather than waiting for the alarm.
     *
     * Otherwise the switch appears to do nothing for up to a day, which reads as broken — the same
     * reason enabling auto-download on a podcast fetches straight away.
     */
    fun setAutoDownloadLiked(enabled: Boolean) {
        com.lightphone.spotify.playback.download.LibraryAutoDownloadSettings
            .setLikedEnabled(libraryAutoPrefs, enabled)
        if (enabled) {
            com.lightphone.spotify.playback.download.LibraryAutoDownload
                .checkNow(getApplication(), force = true)
        }
    }

    fun setAutoDownloadLikedLimit(limit: Int) {
        com.lightphone.spotify.playback.download.LibraryAutoDownloadSettings
            .setLikedLimit(libraryAutoPrefs, limit)
        com.lightphone.spotify.playback.download.LibraryAutoDownload
            .checkNow(getApplication(), force = true)
    }

    fun setAutoDownloadMixes(enabled: Boolean) {
        com.lightphone.spotify.playback.download.LibraryAutoDownloadSettings
            .setMixesEnabled(libraryAutoPrefs, enabled)
        if (enabled) {
            com.lightphone.spotify.playback.download.LibraryAutoDownload
                .checkNow(getApplication(), force = true)
        }
    }

    fun cycleEpisodeSpeed() {
        val next = PodcastSettings.cycleEpisodeSpeed(podcastPreferences)
        controller.setEpisodePlaybackSpeed(next)
    }

    /**
     * Record what is playing so the player can offer it again after a restart.
     *
     * Reads the derived state, which means a radio stream is skipped — its `radio:` uri is not something
     * `play` can load — and a Connect device's track is not: what the speaker is playing is still what
     * *you* were listening to, and resuming it locally is the reasonable next step.
     */
    private fun rememberResumable(state: PlaybackUiState) {
        val uri = state.currentUri
        if (uri == null) {
            // The engine has nothing loaded: a fresh process, or a queue that ran out. Re-offer
            // whatever was written last so the play button is never dead — the original complaint was
            // exactly this screen saying "No song playing" with nothing to press.
            //
            // Re-read the store rather than trusting the in-memory copy, whose position stops being
            // refreshed the moment real playback takes over.
            val saved = playbackResume.saved()
            if (saved != _resumable.value) _resumable.value = saved
            return
        }
        if (uri.startsWith("nts:")) return
        val position = state.positionMs
        if (uri == savedResumeUri) {
            // Same track: only the position moved, and rewriting the metadata every tick would be a
            // preference commit per second — the engine reports position once a second.
            if (position > 0L) playbackResume.savePosition(uri, position)
            return
        }
        val track = TrackMetadata(
            uri = uri,
            title = state.title.orEmpty(),
            artists = state.artist.orEmpty(),
            album = "",
            durationMs = state.durationMs,
            artUrl = state.artUrl,
            albumId = state.albumId,
        )
        playbackResume.save(track, position)
        // Tracked separately from [_resumable], which stays populated while playing: it is only ever
        // *read* when the engine is empty, and using it as the "already saved" flag made every tick
        // fall through to the full eight-field commit above.
        savedResumeUri = uri
    }

    /**
     * Play the track the player is offering, from where it stopped.
     *
     * Load-then-seek, the same shape [playEpisode] uses: neither the engine nor the Web API accepts a
     * start position, so the seek has to wait for the track to actually be loaded — hence waiting on
     * `durationMs > 0` rather than firing immediately.
     */
    fun resumeLastTrack() {
        val saved = _resumable.value ?: return
        // Claim the uri first. The load reports position 0 before the seek lands, and letting that
        // reach the full-save path would overwrite the very position being restored — losing it for
        // good if the app is killed in between.
        savedResumeUri = saved.track.uri
        playTracks(listOf(saved.track), startIndex = 0)
        if (saved.positionMs <= 0L) return
        viewModelScope.launch {
            withTimeoutOrNull(SEEK_WAIT_MS) {
                playback.first { it.currentUri == saved.track.uri && it.durationMs > 0L }
            } ?: return@launch
            controller.seek(saved.positionMs)
        }
    }

    fun setPodcastRetention(value: PodcastRetention) {
        if (value == PodcastSettings.retention) return
        PodcastSettings.setRetention(podcastPreferences, value)
        // Apply straight away rather than at the next episode, so picking "Keep 3" visibly frees space.
        PodcastAutoDownload.pruneNow(getApplication())
    }

    /**
     * Play an episode, picking up where it was left.
     *
     * The seek happens after the engine reports the episode loaded rather than immediately: seeking a
     * track that has not started yet is dropped, which is what made the first version always begin
     * from zero.
     */
    /**
     * Play an episode, resuming where it was left.
     *
     * The resume is applied by [playTracks] now. It used to be a `seek` fired after `play`, gated on
     * `playback.first { it.currentUri == episode.uri && it.durationMs > 0L }` — a guard that stopped
     * working the moment episodes gained a duration, because `PlaybackController.play` seeds
     * `currentUri` and `durationMs` optimistically and synchronously. Both halves were then true on
     * the first emission, so the seek fired before anything was loaded: its own transport job
     * cancelled the in-flight play, and `onTrackChanged` later reset the position to 0 and discarded
     * the pending seek as belonging to the previous track. The bar jumped to the resume point and fell
     * straight back to 0:00.
     *
     * The lesson worth keeping: never gate on state a caller has optimistically seeded — wait on
     * something only the engine can produce, or better, do not race it at all.
     */
    fun playEpisode(episode: SpotifyEpisode, showName: String?) {
        val metadata = episode.toTrackMetadata(showName)
        playTracks(listOf(metadata), startIndex = 0, contextLabel = showName)
    }

    /**
     * Download one episode, filed under its show.
     *
     * Via `downloadCollection` with a single track rather than the plainer `download`, so the episode
     * gets a membership row under `spotify:show:<id>` exactly like an auto-downloaded one. Without
     * that it is an orphan: retention cannot count it, and "Remove downloads" on the show cannot see
     * it. `downloadCollection` upserts the collection, so calling it per episode is safe.
     */
    fun downloadEpisode(episode: SpotifyEpisode, showName: String?, showId: String?) {
        if (!downloadsSupported) return
        val metadata = episode.toTrackMetadata(showName)
        val quality = controller.downloadQualityApiValue()
        if (showId == null) {
            controller.offlineDownloads.download(getApplication(), metadata, quality)
            return
        }
        queueEpisodeDownloads(showId, listOf(episode))
    }

    /**
     * Remember where an episode got to.
     *
     * Called on pause, when the episode changes, and periodically while it plays — see the collector
     * in `init` for why a timer became necessary. The rule itself lives in [EpisodeResume] so it can
     * be tested; it has been the cause of two "always starts from 0:00" reports.
     */
    private fun rememberEpisodePosition(uri: String?, positionMs: Long, durationMs: Long) {
        // The null check is not redundant with isEpisodeUri(): it is what smart-casts `uri` to
        // non-null for the preference calls below.
        if (uri == null || !uri.isEpisodeUri()) return
        val outcome = EpisodeResume.decide(
            positionMs = positionMs,
            durationMs = durationMs,
            finishedTailMs = FINISHED_TAIL_MS,
            floorMs = EpisodeResume.RESUME_FLOOR_MS,
        )
        when (outcome) {
            EpisodeResume.Outcome.Save -> podcastPreferences.setResumePosition(uri, positionMs)
            EpisodeResume.Outcome.ClearFinished,
            EpisodeResume.Outcome.ClearTooEarly,
            -> podcastPreferences.clearResumePosition(uri)
        }
    }

    private fun SpotifyEpisode.toTrackMetadata(showName: String?) = TrackMetadata(
        uri = uri,
        title = name,
        artists = showName ?: "Podcast",
        album = showName ?: "Podcast",
        durationMs = durationMs,
        // The widest image, not the list thumbnail: this one is shown full width.
        artUrl = fullArtUrl,
    )

    fun playRadio(station: RadioStation) {
        radioController.play(station)
        // Click counts are what the directory ranks by; an app that reads them and never reports is
        // freeloading. NTS entries are built in and have no uuid to report.
        if (station.origin == RadioStation.Origin.Directory) {
            viewModelScope.launch { runCatching { radioBrowser.reportClick(station.id) } }
        }
    }

    fun stopRadio() = radioController.stop()

    private val connectController = controller.connect

    /** Device picker state (Spotify Connect). */
    val connect: StateFlow<ConnectUiState> = connectController.state

    private val isRemote: Boolean get() = connectController.state.value.isRemote

    /**
     * What the player offers to continue when the engine has nothing loaded.
     *
     * Cleared the moment something real starts, so it can never linger next to actual playback.
     */
    private val playbackResume = PlaybackResume(app)
    private val _resumable = MutableStateFlow(playbackResume.saved())

    /** Uri whose metadata is already in [playbackResume], so ticks only rewrite the position. */
    private var savedResumeUri: String? = null

    /**
     * Playback state the screens bind to.
     *
     * While a Connect device is active, the remote player's track and position are
     * overlaid onto the local state, so every screen shows what the speaker is doing
     * without knowing that remote playback exists. Session/auth fields always come from
     * the local controller — they describe this app's connection, not the speaker's.
     */
    val playback: StateFlow<PlaybackUiState> =
        combine(
            controller.state,
            connectController.remotePlayback,
            radioController.state,
            _resumable,
        ) { local, remote, radio, resumable ->
            // Radio wins when it is on: it is the thing making sound, and it took Spotify's place
            // rather than sitting alongside it.
            when {
                radio.isActive -> local.withRadio(radio)
                remote != null -> local.withRemote(remote)
                // Nothing loaded, but we know what was playing last time. Showing it — paused, at its
                // old position — is strictly better than "No song playing", and it means the player
                // needs no special case: cover, title and progress bar all read the same fields.
                local.currentUri == null && resumable != null -> local.withResumable(resumable)
                else -> local
            }
        }.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            // Seeded with the overlay applied: `combine` runs in a coroutine, so an unseeded initial
            // value means the player's first frame says "No song playing" and then swaps.
            controller.state.value.let { local ->
                val saved = _resumable.value
                if (local.currentUri == null && saved != null) local.withResumable(saved) else local
            },
        )

    init {
        // Persist an episode's position when playback moves off it — pausing is handled in pause(),
        // but auto-advance and switching episodes are not, and losing the position on those is what
        // makes a podcast app feel broken.
        viewModelScope.launch {
            var lastUri: String? = null
            var lastPosition = 0L
            var lastDuration = 0L
            var lastEpisodeSaveAt = 0L
            playback.collect { state ->
                if (state.currentUri != lastUri) {
                    rememberEpisodePosition(lastUri, lastPosition, lastDuration)
                    lastUri = state.currentUri
                    // Reset, or the next item inherits this one's numbers: they are only overwritten
                    // once it reports a real position, and switching away before that would save the
                    // previous track's position against the new track's uri.
                    lastPosition = 0L
                    lastDuration = 0L
                }
                // Only track a real position: the engine reports 0 briefly while loading, and saving
                // that would wipe the position we are about to restore.
                if (state.positionMs > 0L) lastPosition = state.positionMs
                if (state.durationMs > 0L) lastDuration = state.durationMs
                // Commit an episode's position periodically while it plays, not only when playback
                // moves off it.
                //
                // The event-driven writes miss the ways listening actually ends: the process being
                // killed (this phone is aggressive about that), the app being swiped away without
                // `onCleared` running, and — the common one — pausing from the lock screen or a
                // headset button, which goes straight to `PlaybackController.pauseTransport` and never
                // reaches `AppViewModel.pause()`. All of those left the position unwritten, so the
                // episode restarted from the beginning. `PlaybackResume` is already written every
                // tick; this brings the podcast store in line with it, at one write per
                // EPISODE_POSITION_SAVE_MS rather than one per second.
                if (state.currentUri.isEpisodeUri() && lastPosition > 0L) {
                    val now = System.currentTimeMillis()
                    if (now - lastEpisodeSaveAt > EPISODE_POSITION_SAVE_MS) {
                        lastEpisodeSaveAt = now
                        rememberEpisodePosition(state.currentUri, lastPosition, lastDuration)
                    }
                }
                rememberResumable(state)
            }
        }
    }

    /** Offline downloads (Room-backed). Empty when the backend does not support pins. */
    val downloads: StateFlow<List<com.lightphone.spotify.data.local.DownloadedTrackEntity>> =
        if (controller.capabilities.downloads) {
            com.lightphone.spotify.data.local.PhonoDatabase.get(app)
                .downloadedTrackDao()
                .observeAll()
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } else {
            MutableStateFlow(emptyList())
        }

    /**
     * Byte progress for whatever is downloading right now, so a row can say how far along it is
     * rather than "Downloading…" for four minutes.
     *
     * Straight from the download center's in-memory flow rather than the database, because the
     * fraction changes several times a second and only the throttled milestones reach Room — see
     * [com.lightphone.spotify.playback.download.DownloadProgress].
     */
    val downloadProgress: StateFlow<Map<String, com.lightphone.spotify.playback.download.DownloadProgress>> =
        com.lightphone.spotify.playback.download.SpotifyDownloadCenter.progress

    val downloadCollections: StateFlow<List<com.lightphone.spotify.data.local.DownloadedCollectionWithProgress>> =
        if (controller.capabilities.downloads) {
            com.lightphone.spotify.data.local.PhonoDatabase.get(app)
                .downloadedCollectionDao()
                .observeCollectionsWithProgress(
                    completedState = DownloadStates.COMPLETED,
                    queuedState = DownloadStates.QUEUED,
                    downloadingState = DownloadStates.DOWNLOADING,
                    restartingState = DownloadStates.RESTARTING,
                    failedState = DownloadStates.FAILED,
                )
                .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
        } else {
            MutableStateFlow(emptyList())
        }

    val downloadsSupported: Boolean = controller.capabilities.downloads
    val capabilities: BackendCapabilities = controller.capabilities

    /** Completed offline pin URIs for gray-out / availability checks. */
    val completedDownloadUris: StateFlow<Set<String>> =
        downloads
            .map { rows ->
                rows.asSequence()
                    .filter { it.state == DownloadStates.COMPLETED }
                    .map { it.uri }
                    .toSet()
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptySet())

    /**
     * Whether anything at all is downloaded, used to decide if opening offline is worth redirecting.
     *
     * Reads the same rows as [completedDownloadUris] rather than counting collections, because a
     * collection row exists as soon as a download is queued and would claim there is offline audio
     * while the first track is still arriving.
     */
    val hasDownloadedContent: StateFlow<Boolean> =
        completedDownloadUris
            .map { it.isNotEmpty() }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    fun isTrackDownloaded(uri: String): Boolean =
        downloadsSupported && uri in completedDownloadUris.value

    fun isNetworkOnline(): Boolean = playback.value.networkOnline

    fun observeDownloadCollectionTracks(
        collectionUri: String,
    ): StateFlow<List<com.lightphone.spotify.data.local.DownloadedTrackEntity>> {
        if (!downloadsSupported) return MutableStateFlow(emptyList())
        return com.lightphone.spotify.data.local.PhonoDatabase.get(getApplication())
            .downloadedCollectionDao()
            .observeTracksForCollection(collectionUri)
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** Pin a track for offline playback (uses download quality, not streaming). */
    fun downloadTrack(track: TrackMetadata) {
        if (!downloadsSupported) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.download(getApplication(), track, quality)
    }

    fun removeDownload(track: TrackMetadata) {
        if (!downloadsSupported) return
        val quality = downloads.value.firstOrNull { it.uri == track.uri }?.quality
            ?: controller.downloadQualityApiValue()
        controller.offlineDownloads.remove(getApplication(), track, quality)
    }

    fun removeDownloadCollection(collectionUri: String) {
        if (!downloadsSupported) return
        controller.offlineDownloads.removeCollection(getApplication(), collectionUri)
    }

    /**
     * Put one failed download back in the queue.
     *
     * A failed row used to be a dead end: the automatic retries are three, and after that the only
     * way back was to remove the download and find the track again. Most failures here are a dropped
     * session or a CDN that timed out, so the second attempt usually just works.
     */
    fun retryDownload(trackUri: String) {
        if (!downloadsSupported || trackUri.isBlank()) return
        controller.offlineDownloads.retry(getApplication(), listOf(trackUri))
    }

    /** Retry every failed row in one collection. */
    fun retryFailedDownloads(collectionUri: String) {
        if (!downloadsSupported) return
        viewModelScope.launch {
            val db = PhonoDatabase.get(getApplication())
            val trackDao = db.downloadedTrackDao()
            val failed = db.downloadedCollectionDao()
                .trackUrisForCollection(collectionUri)
                .filter { trackDao.getByUri(it)?.state == DownloadStates.FAILED }
            if (failed.isEmpty()) return@launch
            controller.offlineDownloads.retry(getApplication(), failed)
        }
    }

    /**
     * Download the next [count] episodes of a show that are not on the phone already.
     *
     * "Next" is whatever the episode list is currently showing first, so it means the newest few by
     * default and the next few chronologically when the list reads oldest-first — which is what
     * someone working through a back catalogue wants, and the reason this is not simply "download
     * the latest three".
     *
     * Loads the first page itself when called from Downloads, where the episode list has never been
     * opened and there is nothing in memory to pick from.
     */
    fun downloadNextEpisodes(showId: String, count: Int = NEXT_EPISODES_COUNT) {
        if (!downloadsSupported || showId.isBlank()) return
        viewModelScope.launch {
            if (_podcasts.value.episodesByShow[showId]?.episodes.isNullOrEmpty()) {
                loadShowEpisodes(showId)
                // Wait for the page rather than polling: the state flow emits when it lands, and a
                // show that never loads leaves this coroutine parked, not spinning.
                podcasts.first { it.episodesByShow[showId]?.loading == false }
            }
            val episodes = _podcasts.value.episodesByShow[showId]?.episodes.orEmpty()
            val alreadyHave = PhonoDatabase.get(getApplication()).downloadedTrackDao()
                .getAll()
                .filter { it.state != DownloadStates.FAILED }
                .mapTo(HashSet()) { it.uri }
            val next = episodes
                .filter { it.isStreamable && !PodcastSettings.isUnplayable(it.uri) }
                .filterNot { it.uri in alreadyHave }
                .take(count)
            if (next.isEmpty()) return@launch
            queueEpisodeDownloads(showId, next)
        }
    }

    /**
     * Queue a batch of episodes under their show, as one collection write.
     *
     * Shared by SELECT, by "next three" and by a single long-press, so all three file the episode the
     * same way an automatic download would — one `downloadCollection` call, membership under
     * `spotify:show:<id>`, and the uris recorded as hand-picked so retention leaves them alone.
     */
    private fun queueEpisodeDownloads(showId: String, episodes: List<SpotifyEpisode>) {
        if (episodes.isEmpty()) return
        val show = _podcasts.value.shows.firstOrNull { it.id == showId }
        podcastPreferences.addKeptEpisodes(showId, episodes.map { it.uri })
        controller.offlineDownloads.downloadCollection(
            context = getApplication(),
            collectionUri = "spotify:show:$showId",
            type = "show",
            // Header-sized art, matching what auto-download files: Downloads shows this full width,
            // where a list thumbnail looks worst.
            name = show?.name ?: "Podcast",
            artUrl = show?.detailArtUrl,
            tracks = episodes.map { it.toTrackMetadata(show?.name) },
            quality = controller.downloadQualityApiValue(),
        )
    }

    /** Download every track in the current album detail. */
    fun downloadCurrentAlbum() {
        if (!downloadsSupported) return
        val album = _albumDetail.value.album ?: return
        val tracks = album.tracks?.items.orEmpty().map { it.toMetadata() }
        if (tracks.isEmpty()) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.downloadCollection(
            context = getApplication(),
            collectionUri = collectionUri(
                backendChoice, CollectionKind.Album, album.id, album.uri,
            ),
            type = "album",
            name = album.name,
            artUrl = album.images.firstOrNull()?.url,
            tracks = tracks,
            quality = quality,
        )
    }

    /** Asks first — see [askRemoveDownload]. The header icon is easy to hit by accident. */
    fun removeCurrentAlbumDownloads() {
        if (!downloadsSupported) return
        val album = _albumDetail.value.album ?: return
        askRemoveDownload(
            collectionUri(backendChoice, CollectionKind.Album, album.id, album.uri),
        )
    }

    /** Download every track in the current playlist detail. */
    fun downloadCurrentPlaylist() {
        if (!downloadsSupported) return
        val detail = _playlistDetail.value.detail ?: return
        val tracks = _playlistDetail.value.tracks.map { it.track.toMetadata() }
        if (tracks.isEmpty()) return
        val quality = controller.downloadQualityApiValue()
        controller.offlineDownloads.downloadCollection(
            context = getApplication(),
            collectionUri = collectionUri(
                backendChoice, CollectionKind.Playlist, detail.id, detail.uri,
            ),
            type = "playlist",
            name = detail.name,
            artUrl = detail.images?.firstOrNull()?.url,
            tracks = tracks,
            quality = quality,
        )
    }

    /** Asks first — see [askRemoveDownload]. */
    fun removeCurrentPlaylistDownloads() {
        if (!downloadsSupported) return
        val detail = _playlistDetail.value.detail ?: return
        askRemoveDownload(
            collectionUri(backendChoice, CollectionKind.Playlist, detail.id, detail.uri),
        )
    }

    /** Aggregate download state for album/playlist header + menus. */
    fun collectionDownloadUi(trackUris: List<String>): CollectionDownloadUi {
        if (!downloadsSupported || trackUris.isEmpty()) return CollectionDownloadUi.None
        val byUri = downloads.value.associateBy { it.uri }
        var completed = 0
        var inProgress = 0
        for (uri in trackUris) {
            when (byUri[uri]?.state) {
                DownloadStates.COMPLETED -> completed++
                DownloadStates.DOWNLOADING,
                DownloadStates.QUEUED,
                DownloadStates.RESTARTING,
                -> inProgress++
                else -> Unit
            }
        }
        return when {
            completed == trackUris.size -> CollectionDownloadUi.Complete
            // Only a genuine in-flight download shows the spinner.
            //
            // This used to also report Downloading for any partially-downloaded collection, which
            // meant the header span forever with nothing running: download a few tracks
            // individually, or have a download fail or get interrupted, and the album was
            // permanently "downloading". A partial collection now reads as not-downloaded, so the
            // control is an actionable download arrow that enqueues what is missing.
            inProgress > 0 -> CollectionDownloadUi.Downloading
            else -> CollectionDownloadUi.None
        }
    }

    /** True if this collection URI is fully (or partially) present in offline pins. */
    fun isCollectionDownloaded(collectionUri: String): Boolean {
        if (!downloadsSupported) return false
        val row = downloadCollections.value.firstOrNull { it.uri == collectionUri } ?: return false
        return row.track_count > 0 && row.completed_count >= row.track_count
    }

    fun isCollectionDownloading(collectionUri: String): Boolean {
        if (!downloadsSupported) return false
        val row = downloadCollections.value.firstOrNull { it.uri == collectionUri } ?: return false
        // Same fix as collectionDownloadUi: partial-and-idle is not downloading. Counting it as
        // such also made the context menu offer "Remove download" for a collection that had never
        // finished one.
        return row.in_progress_count > 0
    }

    fun downloadAlbumById(albumId: String, uri: String = "") {
        if (!downloadsSupported) return
        viewModelScope.launch {
            runCatching {
                val result = controller.albumDetail(albumId)
                val album = result.album
                val tracks = album.tracks?.items.orEmpty().map { it.toMetadata() }
                if (tracks.isEmpty()) return@runCatching
                val quality = controller.downloadQualityApiValue()
                controller.offlineDownloads.downloadCollection(
                    context = getApplication(),
                    collectionUri = collectionUri(
                        backendChoice, CollectionKind.Album, albumId, uri.ifBlank { album.uri },
                    ),
                    type = "album",
                    name = album.name,
                    artUrl = album.images.firstOrNull()?.url,
                    tracks = tracks,
                    quality = quality,
                )
            }.onFailure { e ->
                android.util.Log.e("Downloads", "downloadAlbumById failed", e)
            }
        }
    }

    fun downloadPlaylistById(playlistId: String, uri: String = "") {
        if (!downloadsSupported) return
        viewModelScope.launch {
            runCatching {
                val result = controller.playlistDetail(playlistId)
                val detail = result.detail
                val tracks = result.tracks.mapNotNull { it.track?.toMetadata() }
                if (tracks.isEmpty()) return@runCatching
                val quality = controller.downloadQualityApiValue()
                controller.offlineDownloads.downloadCollection(
                    context = getApplication(),
                    collectionUri = collectionUri(
                        backendChoice,
                        CollectionKind.Playlist,
                        playlistId,
                        uri.ifBlank { detail.uri },
                    ),
                    type = "playlist",
                    name = detail.name,
                    artUrl = detail.images?.firstOrNull()?.url,
                    tracks = tracks,
                    quality = quality,
                )
            }.onFailure { e ->
                android.util.Log.e("Downloads", "downloadPlaylistById failed", e)
            }
        }
    }

    private val _likedTracks = MutableStateFlow(LibraryListUiState<LikedTrackEntity>())
    val likedTracks: StateFlow<LibraryListUiState<LikedTrackEntity>> = _likedTracks.asStateFlow()

    private val _libraryBootstrapping = MutableStateFlow(false)
    val libraryBootstrapping: StateFlow<Boolean> = _libraryBootstrapping.asStateFlow()

    private val _savedAlbums = MutableStateFlow(LibraryListUiState<SavedAlbumEntity>())
    val savedAlbums: StateFlow<LibraryListUiState<SavedAlbumEntity>> = _savedAlbums.asStateFlow()

    private val _playlists = MutableStateFlow(PlaylistsUiState())
    val playlists: StateFlow<PlaylistsUiState> = _playlists.asStateFlow()

    private var likedTracksStarted = false
    private var onLoggedInCalled = false
    private var savedAlbumsStarted = false
    private var playlistsStarted = false
    private var likedFillJob: Job? = null
    private var likedRefreshJob: Job? = null
    private var likedFillRetryJob: Job? = null
    private var likedLookaheadJob: Job? = null
    private var savedFillJob: Job? = null
    private var savedRefreshJob: Job? = null
    private var savedFillRetryJob: Job? = null
    private var savedLookaheadJob: Job? = null
    private var playlistsFillJob: Job? = null
    private var playlistsRefreshJob: Job? = null
    private var playlistsFillRetryJob: Job? = null
    private var playlistsLookaheadJob: Job? = null
    private var likedFillRetries = 0
    private var savedFillRetries = 0
    private var playlistsFillRetries = 0

    private var sessionGeneration = 0
    private var searchRequestId = 0L

    @Volatile private var scrubResyncLocked = false
    private var pendingLikedRefresh = false
    private var pendingSavedRefresh = false
    private var pendingPlaylistsRefresh = false

    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    private val _albumDetail = MutableStateFlow(AlbumDetailState())
    val albumDetail: StateFlow<AlbumDetailState> = _albumDetail.asStateFlow()

    private val _artistDetail = MutableStateFlow(ArtistDetailState())
    val artistDetail: StateFlow<ArtistDetailState> = _artistDetail.asStateFlow()

    private val _playingExtras = MutableStateFlow(PlayingExtrasState())
    val playingExtras: StateFlow<PlayingExtrasState> = _playingExtras.asStateFlow()

    private val _settings = MutableStateFlow(SettingsUiState(darkTheme = themePreferences.isDarkTheme()))
    val settings: StateFlow<SettingsUiState> = _settings.asStateFlow()

    private val _playlistDetail = MutableStateFlow(PlaylistDetailState())
    val playlistDetail: StateFlow<PlaylistDetailState> = _playlistDetail.asStateFlow()

    private val _createPlaylist = MutableStateFlow(CreatePlaylistState())
    val createPlaylist: StateFlow<CreatePlaylistState> = _createPlaylist.asStateFlow()

    private val _playlistPicker = MutableStateFlow(PlaylistPickerState())
    val playlistPicker: StateFlow<PlaylistPickerState> = _playlistPicker.asStateFlow()

    private val _contextMenu = MutableStateFlow(ContextMenuUiState())
    val contextMenu: StateFlow<ContextMenuUiState> = _contextMenu.asStateFlow()

    private var loadedPlaylistId: String? = null
    private var playlistPickerLoadGen = 0

    init {
        refreshSettings()
        viewModelScope.launch {
            playback
                .map { it.currentUri }
                .distinctUntilChanged()
                .collect { uri -> onCurrentTrackChanged(uri) }
        }
        viewModelScope.launch {
            playback
                .map { it.networkOnline }
                .distinctUntilChanged()
                .collect { online ->
                    if (!online) clearLibrarySyncErrorsForOffline()
                }
        }
        viewModelScope.launch {
            controller.sessionEvents.collect { event ->
                when (event) {
                    SessionEvent.SigningOut -> cancelPlaylistLibraryJobs()
                    SessionEvent.SignedOut -> Unit
                }
            }
        }
        controller.onSessionRestored = { onPlaybackSessionRestored() }
    }

    /** Drop sync banners when offline — navbar already shows Device offline. */
    private fun clearLibrarySyncErrorsForOffline() {
        _likedTracks.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _savedAlbums.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _playlists.update { it.copy(error = null, refreshing = false, initialLoading = false) }
        _search.update { it.copy(error = null, refreshError = null, refreshing = false, initialLoading = false) }
    }

    private fun cancelPlaylistLibraryJobs() {
        playlistsRefreshJob?.cancel()
        playlistsFillJob?.cancel()
        playlistsFillRetryJob?.cancel()
        playlistsLookaheadJob?.cancel()
    }

    private fun onPlaybackSessionRestored() {
        clearStalePlaybackSignInErrors()
    }

    private fun clearStalePlaybackSignInErrors() {
        fun isStale(msg: String?): Boolean {
            if (msg == null) return false
            return msg.contains("sign in to spotify playback", ignoreCase = true) ||
                msg.contains("can't reach spotify playback", ignoreCase = true) ||
                msg.contains("playback sign-in", ignoreCase = true)
        }
        if (isStale(_playlists.value.error)) {
            _playlists.update { it.copy(error = null) }
            if (playlistsStarted && playlistsRefreshJob?.isActive != true) {
                refreshPlaylists()
            }
        }
        if (isStale(_playlistDetail.value.error)) {
            _playlistDetail.update { it.copy(error = null) }
        }
        if (isStale(_artistDetail.value.error)) {
            _artistDetail.update { it.copy(error = null) }
        }
    }

    private fun resetSessionUiState() {
        sessionGeneration++
        likedTracksStarted = false
        savedAlbumsStarted = false
        playlistsStarted = false
        onLoggedInCalled = false
        likedFillJob?.cancel()
        likedRefreshJob?.cancel()
        likedFillRetryJob?.cancel()
        savedFillJob?.cancel()
        savedRefreshJob?.cancel()
        savedFillRetryJob?.cancel()
        playlistsFillJob?.cancel()
        playlistsRefreshJob?.cancel()
        playlistsFillRetryJob?.cancel()
        likedLookaheadJob?.cancel()
        savedLookaheadJob?.cancel()
        playlistsLookaheadJob?.cancel()
        searchJob?.cancel()
        playingExtrasJob?.cancel()
        playingExtrasLoadedForUri = null
        likedFillRetries = 0
        savedFillRetries = 0
        playlistsFillRetries = 0
        pendingLikedRefresh = false
        pendingSavedRefresh = false
        pendingPlaylistsRefresh = false
        loadedPlaylistId = null
        // Increment (never reset to 0) so a stale pre-reset load's captured
        // generation can never collide with the next load's generation.
        playlistPickerLoadGen++
        _libraryBootstrapping.value = false
        _likedTracks.value = LibraryListUiState()
        _savedAlbums.value = LibraryListUiState()
        _playlists.value = PlaylistsUiState()
        _search.value = SearchUiState()
        _albumDetail.value = AlbumDetailState()
        _artistDetail.value = ArtistDetailState()
        _playlistDetail.value = PlaylistDetailState()
        _playlistPicker.value = PlaylistPickerState()
        _createPlaylist.value = CreatePlaylistState()
        _playingExtras.value = PlayingExtrasState()
        _contextMenu.value = ContextMenuUiState()
    }

    private fun onCurrentTrackChanged(uri: String?) {
        if (uri == null) {
            playingExtrasJob?.cancel()
            playingExtrasLoadedForUri = null
            _playingExtras.value = PlayingExtrasState()
            return
        }
        if (uri != playingExtrasLoadedForUri) {
            playingExtrasLoadedForUri = null
            _playingExtras.update {
                it.copy(isTrackSaved = false, savePending = false, saveError = null)
            }
        }
        refreshPlayingScreen()
    }

    fun refreshSettings() {
        viewModelScope.launch {
            val snap = kotlinx.coroutines.withContext(Dispatchers.IO) {
                controller.loadSettings()
            }
            val current = _settings.value
            _settings.value = snap.toUiState(
                showAdvanced = current.showAdvanced,
                darkTheme = current.darkTheme,
                downloadQuality = if (capabilities.spotifyStreamingQuality) {
                    controller.getSpotifyDownloadQuality()
                } else {
                    current.downloadQuality
                },
            )
        }
    }

    suspend fun beginLogin(): String = controller.beginLogin()

    fun clearLoginError() = controller.clearLoginError()

    fun completeLogin(code: String, state: String? = null) {
        controller.completeLogin(code, state) { result ->
            if (result.isSuccess) onLoggedIn()
        }
    }

    fun saveWebApiCredentials(clientId: String, clientSecret: String) {
        controller.saveWebApiCredentials(clientId, clientSecret)
    }

    fun buildWebApiAuthorizeUrl(): String = controller.buildWebApiAuthorizeUrl()

    /** Send the user back to Step 2 to mint a token that includes the Connect scopes. */
    fun beginWebApiReauthorize() = controller.reauthorizeWebApi()

    fun completeWebApiAuth(code: String, state: String?, onResult: (Result<Unit>) -> Unit) {
        controller.completeWebApiAuth(code, state, onResult)
    }

    fun onLoggedIn() {
        // Spotify Liked/Albums need the Step-2 Web API bearer. Calling this after
        // playback-only login would start empty syncs, clear the splash, and leave
        // Albums broken until a force-stop — LightOS can't recover from that.
        if (backendChoice == BackendChoice.SPOTIFY && !playback.value.webApiReady) {
            return
        }
        if (onLoggedInCalled) {
            refreshLikedTracks()
            refreshSavedAlbums()
            refreshPlaylists()
            return
        }
        onLoggedInCalled = true
        val cacheEmpty =
            _likedTracks.value.items.isEmpty() &&
                _savedAlbums.value.items.isEmpty() &&
                _playlists.value.items.isEmpty()
        if (cacheEmpty) {
            _libraryBootstrapping.value = true
        }
        ensureLikedTracksLoaded()
        ensureSavedAlbumsLoaded()
        viewModelScope.launch {
            try {
                withTimeoutOrNull(WARM_TIMEOUT_MS) {
                    controller.warmSpclientSession()
                } ?: android.util.Log.w(
                    "AppViewModel",
                    "warmSpclientSession timed out after ${WARM_TIMEOUT_MS}ms; loading playlists anyway",
                )
                ensurePlaylistsLoaded()
                if (!_libraryBootstrapping.value) return@launch
                // First launch: hold the splash until first pages land, then drain
                // the rest of the library (even on cellular) so tabs open populated.
                withTimeoutOrNull(LIBRARY_BOOTSTRAP_TIMEOUT_MS) {
                    combine(likedTracks, savedAlbums, playlists) { liked, albums, lists ->
                        !liked.initialLoading && !albums.initialLoading && !lists.initialLoading
                    }.first { it }
                    coroutineScope {
                        listOf(
                            async { awaitLikedTracksFilled() },
                            async { awaitSavedAlbumsFilled() },
                            async { awaitPlaylistsFilled() },
                        ).awaitAll()
                    }
                } ?: android.util.Log.w(
                    "AppViewModel",
                    "library bootstrap timed out after ${LIBRARY_BOOTSTRAP_TIMEOUT_MS}ms; opening shell",
                )
            } finally {
                if (_libraryBootstrapping.value) {
                    _libraryBootstrapping.value = false
                }
            }
        }
    }

    fun ensureLikedTracksLoaded() {
        if (likedTracksStarted) return
        likedTracksStarted = true
        val gen = sessionGeneration
        _likedTracks.value = _likedTracks.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.likedTracksUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _likedTracks.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshLikedTracks()
    }

    fun refreshLikedTracks() {
        if (scrubResyncLocked) { pendingLikedRefresh = true; return }
        if (likedFillJob?.isActive == true) {
            pendingLikedRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _likedTracks.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        likedFillJob?.cancel()
        likedFillJob = null
        likedFillRetryJob?.cancel()
        likedFillRetryJob = null
        likedLookaheadJob?.cancel()
        likedLookaheadJob = null
        likedRefreshJob?.cancel()
        likedRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _likedTracks.value.items.isNotEmpty()
            _likedTracks.update {
                it.copy(
                    refreshing = hadItems,
                    initialLoading = !hadItems,
                    error = null,
                )
            }
            runCatching { controller.refreshLikedTracks() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _likedTracks.update { it.copy(error = e.message ?: "Could not load liked songs") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _likedTracks.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.likedTracksNeedsFill()) {
                startLikedTracksFill()
            }
        }
    }

    fun ensureLikedTracksBufferAhead(lastVisible: Int) {
        if (likedFillJob?.isActive == true) return
        val state = _likedTracks.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (likedLookaheadJob?.isActive == true) return
        likedLookaheadJob = viewModelScope.launch {
            while (_likedTracks.value.items.size < target && _likedTracks.value.hasMore) {
                val hasMore = runCatching { controller.appendLikedTracks() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append liked tracks failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    private fun startLikedTracksFill(force: Boolean = false) {
        if (likedFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        likedFillJob = viewModelScope.launch {
            fillLikedTracksBlocking(force = force)
        }
    }

    /** Drain remaining liked pages. [force] ignores the Wi‑Fi-only gate (first-login splash). */
    private suspend fun fillLikedTracksBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.likedTracksNeedsFill()) return
        val gen = sessionGeneration
        _likedTracks.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingLikedTracks() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.likedTracksNeedsFill()) {
                        likedFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill liked tracks failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _likedTracks.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && likedFillRetries < 3 && controller.likedTracksNeedsFill()) {
                        likedFillRetries++
                        likedFillRetryJob = viewModelScope.launch {
                            delay(2000L * likedFillRetries)
                            if (gen == sessionGeneration) startLikedTracksFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _likedTracks.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    /** Join an in-flight fill, or start a forced drain (bootstrap). */
    private suspend fun awaitLikedTracksFilled() {
        likedFillJob?.takeIf { it.isActive }?.join()
        if (!controller.likedTracksNeedsFill()) return
        val job = viewModelScope.launch { fillLikedTracksBlocking(force = true) }
        likedFillJob = job
        job.join()
    }

    fun resumeLikedTracksFillIfNeeded() {
        if (likedFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.likedTracksNeedsFill()) {
                likedFillRetries = 0
                startLikedTracksFill()
            }
        }
    }

    fun onScrubJumpStart() { scrubResyncLocked = true }

    fun onScrubJumpEnd() {
        scrubResyncLocked = false
        runPendingLibraryRefresh()
    }

    private fun runPendingLibraryRefresh() {
        if (pendingLikedRefresh) { pendingLikedRefresh = false; refreshLikedTracks() }
        if (pendingSavedRefresh) { pendingSavedRefresh = false; refreshSavedAlbums() }
        if (pendingPlaylistsRefresh) { pendingPlaylistsRefresh = false; refreshPlaylists() }
    }

    fun ensureSavedAlbumsLoaded() {
        if (savedAlbumsStarted) return
        savedAlbumsStarted = true
        val gen = sessionGeneration
        _savedAlbums.value = _savedAlbums.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.savedAlbumsUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _savedAlbums.update { it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore) }
            }
        }
        refreshSavedAlbums()
    }

    fun refreshSavedAlbums() {
        if (scrubResyncLocked) { pendingSavedRefresh = true; return }
        if (savedFillJob?.isActive == true) {
            pendingSavedRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _savedAlbums.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        savedFillJob?.cancel()
        savedFillJob = null
        savedFillRetryJob?.cancel()
        savedFillRetryJob = null
        savedLookaheadJob?.cancel()
        savedLookaheadJob = null
        savedRefreshJob?.cancel()
        savedRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _savedAlbums.value.items.isNotEmpty()
            if (!hadItems) {
                _savedAlbums.update { it.copy(initialLoading = true, error = null) }
            } else {
                _savedAlbums.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshSavedAlbums() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _savedAlbums.update { it.copy(error = e.message ?: "Could not load albums") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _savedAlbums.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.savedAlbumsNeedsFill()) {
                startSavedAlbumsFill()
            }
        }
    }

    fun ensureSavedAlbumsBufferAhead(lastVisible: Int) {
        if (savedFillJob?.isActive == true) return
        val state = _savedAlbums.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (savedLookaheadJob?.isActive == true) return
        savedLookaheadJob = viewModelScope.launch {
            while (_savedAlbums.value.items.size < target && _savedAlbums.value.hasMore) {
                val hasMore = runCatching { controller.appendSavedAlbums() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append saved albums failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    suspend fun scrollLikedTracksToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _likedTracks.value.items
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    suspend fun scrollSavedAlbumsToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _savedAlbums.value.items
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    private fun startSavedAlbumsFill(force: Boolean = false) {
        if (savedFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        savedFillJob = viewModelScope.launch {
            fillSavedAlbumsBlocking(force = force)
        }
    }

    private suspend fun fillSavedAlbumsBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.savedAlbumsNeedsFill()) return
        val gen = sessionGeneration
        _savedAlbums.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingSavedAlbums() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.savedAlbumsNeedsFill()) {
                        savedFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill saved albums failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _savedAlbums.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && savedFillRetries < 3 && controller.savedAlbumsNeedsFill()) {
                        savedFillRetries++
                        savedFillRetryJob = viewModelScope.launch {
                            delay(2000L * savedFillRetries)
                            if (gen == sessionGeneration) startSavedAlbumsFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _savedAlbums.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    private suspend fun awaitSavedAlbumsFilled() {
        savedFillJob?.takeIf { it.isActive }?.join()
        if (!controller.savedAlbumsNeedsFill()) return
        val job = viewModelScope.launch { fillSavedAlbumsBlocking(force = true) }
        savedFillJob = job
        job.join()
    }

    fun resumeSavedAlbumsFillIfNeeded() {
        if (savedFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.savedAlbumsNeedsFill()) {
                savedFillRetries = 0
                startSavedAlbumsFill()
            }
        }
    }

    fun ensurePlaylistsLoaded() {
        if (playlistsStarted) return
        playlistsStarted = true
        val gen = sessionGeneration
        _playlists.value = _playlists.value.copy(initialLoading = true)
        viewModelScope.launch {
            controller.playlistsUiFlow().collect { (items, remoteTotal, hasMore) ->
                if (gen != sessionGeneration) return@collect
                _playlists.update {
                    it.copy(items = items, remoteTotal = remoteTotal, hasMore = hasMore)
                }
            }
        }
        viewModelScope.launch {
            runCatching { controller.currentUserId() }
                .onSuccess { userId -> _playlists.update { it.copy(currentUserId = userId) } }
        }
        refreshPlaylists()
    }

    private val _likedFilter = MutableStateFlow(LikedFilter.Songs)

    /** Songs or Albums, for the combined Liked tab. Not persisted: songs is the right default. */
    val likedFilter: StateFlow<LikedFilter> = _likedFilter.asStateFlow()

    fun setLikedFilter(filter: LikedFilter) {
        _likedFilter.value = filter
    }

    fun setPlaylistsFilter(filter: PlaylistFilter) {
        _playlists.update { it.copy(filter = filter) }
    }

    fun refreshPlaylists() {
        if (scrubResyncLocked) { pendingPlaylistsRefresh = true; return }
        if (playlistsFillJob?.isActive == true) {
            pendingPlaylistsRefresh = true
            return
        }
        if (!isNetworkOnline()) {
            _playlists.update {
                it.copy(error = null, refreshing = false, initialLoading = false)
            }
            return
        }
        playlistsFillJob?.cancel()
        playlistsFillJob = null
        playlistsFillRetryJob?.cancel()
        playlistsFillRetryJob = null
        playlistsLookaheadJob?.cancel()
        playlistsLookaheadJob = null
        playlistsRefreshJob?.cancel()
        playlistsRefreshJob = viewModelScope.launch {
            val gen = sessionGeneration
            val hadItems = _playlists.value.items.isNotEmpty()
            if (!hadItems) {
                _playlists.update { it.copy(initialLoading = true, error = null) }
            } else {
                _playlists.update { it.copy(refreshing = true, error = null) }
            }
            runCatching { controller.refreshPlaylists() }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    if (gen == sessionGeneration && isNetworkOnline()) {
                        _playlists.update { it.copy(error = e.message ?: "Could not load playlists") }
                    }
                }
            if (gen != sessionGeneration) return@launch
            _playlists.update { it.copy(refreshing = false, initialLoading = false) }
            if (controller.playlistsNeedsFill()) {
                startPlaylistsFill()
            }
        }
    }

    fun ensurePlaylistsBufferAhead(lastVisible: Int) {
        if (playlistsFillJob?.isActive == true) return
        val state = _playlists.value
        val target = lastVisible + LOOKAHEAD_ROWS
        if (state.items.size >= target || !state.hasMore) return
        if (playlistsLookaheadJob?.isActive == true) return
        playlistsLookaheadJob = viewModelScope.launch {
            while (_playlists.value.items.size < target && _playlists.value.hasMore) {
                val hasMore = runCatching { controller.appendPlaylists() }
                    .onFailure { e ->
                        android.util.Log.e("Library", "append playlists failed", e)
                    }
                    .getOrDefault(false)
                if (!hasMore) break
            }
        }
    }

    suspend fun scrollPlaylistsToIndex(listState: LazyListState, targetIndex: Int) {
        val items = _playlists.value.displayItems
        if (items.isEmpty()) return
        if (targetIndex > items.lastIndex) {
            android.util.Log.w("Library", "scrub target $targetIndex > lastIndex ${items.lastIndex}")
            return
        }
        listState.scrollToItem(targetIndex)
    }

    private fun startPlaylistsFill(force: Boolean = false) {
        if (playlistsFillJob?.isActive == true) return
        if (!force && !controller.isUnmeteredNetwork()) return
        playlistsFillJob = viewModelScope.launch {
            fillPlaylistsBlocking(force = force)
        }
    }

    private suspend fun fillPlaylistsBlocking(force: Boolean) {
        if (!force && !controller.isUnmeteredNetwork()) return
        if (!controller.playlistsNeedsFill()) return
        val gen = sessionGeneration
        _playlists.update { it.copy(appending = true) }
        try {
            runCatching { controller.fillRemainingPlaylists() }
                .onSuccess {
                    if (gen == sessionGeneration && !controller.playlistsNeedsFill()) {
                        playlistsFillRetries = 0
                    }
                }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    android.util.Log.e("Library", "fill playlists failed", e)
                    if (gen != sessionGeneration) return@onFailure
                    _playlists.update {
                        it.copy(error = it.error ?: "Library sync incomplete — pull to retry")
                    }
                    if (!force && playlistsFillRetries < 3 && controller.playlistsNeedsFill()) {
                        playlistsFillRetries++
                        playlistsFillRetryJob = viewModelScope.launch {
                            delay(2000L * playlistsFillRetries)
                            if (gen == sessionGeneration) startPlaylistsFill()
                        }
                    }
                }
        } finally {
            if (gen == sessionGeneration) {
                _playlists.update { it.copy(appending = false) }
                runPendingLibraryRefresh()
            }
        }
    }

    private suspend fun awaitPlaylistsFilled() {
        playlistsFillJob?.takeIf { it.isActive }?.join()
        if (!controller.playlistsNeedsFill()) return
        val job = viewModelScope.launch { fillPlaylistsBlocking(force = true) }
        playlistsFillJob = job
        job.join()
    }

    fun resumePlaylistsFillIfNeeded() {
        if (playlistsFillJob?.isActive == true) return
        if (!controller.isUnmeteredNetwork()) return
        viewModelScope.launch {
            if (controller.playlistsNeedsFill()) {
                playlistsFillRetries = 0
                startPlaylistsFill()
            }
        }
    }

    fun loadPlaylistDetail(playlistId: String) {
        if (loadedPlaylistId == playlistId &&
            _playlistDetail.value.detail?.id == playlistId &&
            !_playlistDetail.value.loading
        ) {
            return
        }
        loadedPlaylistId = playlistId
        _playlistDetail.value = PlaylistDetailState(
            loading = true,
            requestedId = playlistId,
        )
        viewModelScope.launch {
            runCatching { controller.playlistDetail(playlistId) }
                .onSuccess { result ->
                    // Bail if the user has since navigated to a different playlist (or
                    // signed out) while this request was in flight — otherwise a slow
                    // response for playlist A can land on top of playlist B's screen.
                    if (_playlistDetail.value.requestedId != playlistId) return@onSuccess
                    _playlistDetail.value = PlaylistDetailState(
                        requestedId = playlistId,
                        detail = result.detail,
                        tracks = result.tracks.mapNotNull { item ->
                            item.track?.let { track ->
                                PlaylistDetailTrackRow(
                                    track = track,
                                    addedAt = item.addedAt,
                                    uri = track.uri,
                                )
                            }
                        },
                        snapshotId = result.detail.snapshotId,
                        isEditable = result.isEditable,
                        isInLibrary = result.isInLibrary,
                    )
                }
                .onFailure { e ->
                    if (_playlistDetail.value.requestedId != playlistId) return@onFailure
                    _playlistDetail.value = PlaylistDetailState(
                        requestedId = playlistId,
                        error = e.message ?: "Could not load playlist",
                    )
                }
        }
    }

    fun togglePlaylistEditMode() {
        _playlistDetail.update { it.copy(editMode = !it.editMode, mutationError = null) }
    }

    fun togglePlaylistLibrary(playlistId: String) {
        viewModelScope.launch {
            val current = _playlistDetail.value
            if (current.saving || current.mutating) return@launch
            _playlistDetail.update { it.copy(saving = true, mutationError = null) }
            val result = runCatching {
                if (current.isInLibrary) {
                    controller.unfollowPlaylist(playlistId)
                } else {
                    controller.followPlaylist(playlistId)
                }
            }
            _playlistDetail.update {
                it.copy(
                    saving = false,
                    isInLibrary = if (result.isSuccess) !it.isInLibrary else it.isInLibrary,
                    mutationError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching { controller.renamePlaylist(playlistId, name) }
                .onSuccess { detail ->
                    _playlistDetail.update {
                        it.copy(detail = detail, mutating = false, snapshotId = detail.snapshotId)
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update {
                        it.copy(mutating = false, mutationError = e.message)
                    }
                }
        }
    }

    fun removePlaylistTrack(playlistId: String, index: Int) {
        val state = _playlistDetail.value
        val row = state.tracks.getOrNull(index) ?: return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching {
                controller.removeTrackFromPlaylist(playlistId, row.uri, state.snapshotId)
            }
                .onSuccess { snapshotId ->
                    val updated = state.tracks.toMutableList().apply { removeAt(index) }
                    _playlistDetail.update {
                        it.copy(
                            tracks = updated,
                            mutating = false,
                            snapshotId = snapshotId ?: it.snapshotId,
                            detail = it.detail?.copy(
                                tracks = com.lightphone.spotify.data.SpotifyPlaylistTracksRef(
                                    total = updated.size,
                                ),
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update { it.copy(mutating = false, mutationError = e.message) }
                }
        }
    }

    fun movePlaylistTrack(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        val state = _playlistDetail.value
        if (fromIndex !in state.tracks.indices || toIndex !in state.tracks.indices) return
        viewModelScope.launch {
            _playlistDetail.update { it.copy(mutating = true, mutationError = null) }
            runCatching {
                controller.reorderPlaylistTrack(playlistId, fromIndex, toIndex, state.snapshotId)
            }
                .onSuccess { snapshotId ->
                    val updated = state.tracks.toMutableList()
                    val item = updated.removeAt(fromIndex)
                    updated.add(toIndex, item)
                    _playlistDetail.update {
                        it.copy(
                            tracks = updated,
                            mutating = false,
                            snapshotId = snapshotId ?: it.snapshotId,
                        )
                    }
                }
                .onFailure { e ->
                    _playlistDetail.update { it.copy(mutating = false, mutationError = e.message) }
                }
        }
    }

    fun playPlaylistFrom(playlistId: String, startIndex: Int) {
        viewModelScope.launch {
            val tracks = _playlistDetail.value.tracks
                .drop(startIndex)
                .map { it.track.toMetadata() }
            if (tracks.isEmpty()) {
                val fetched = runCatching { controller.playlistTracks(playlistId) }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    playTracks(fetched, 0, _playlistDetail.value.detail?.name)
                }
            } else {
                playTracks(tracks, 0, _playlistDetail.value.detail?.name)
            }
        }
    }

    fun createPlaylist(name: String, isPublic: Boolean, onCreated: (String, String) -> Unit) {
        viewModelScope.launch {
            _createPlaylist.update { CreatePlaylistState(creating = true) }
            runCatching { controller.createPlaylist(name, isPublic) }
                .onSuccess { playlist ->
                    _createPlaylist.value = CreatePlaylistState()
                    onCreated(playlist.id, playlist.name)
                }
                .onFailure { e ->
                    _createPlaylist.value = CreatePlaylistState(
                        creating = false,
                        error = e.message ?: "Could not create playlist",
                    )
                }
        }
    }

    fun resetCreatePlaylistState() {
        _createPlaylist.value = CreatePlaylistState()
    }

    fun loadPlaylistPicker(trackUri: String) {
        ensurePlaylistsLoaded()
        controller.schedulePlaylistUriIndexSync()
        if (_playlistPicker.value.trackUri != trackUri) {
            applyPlaylistPickerInitialState(trackUri)
        }
        val gen = ++playlistPickerLoadGen
        viewModelScope.launch {
            loadPlaylistPickerMembership(trackUri, gen)
        }
    }

    /** Playlists the current user can add tracks to, from the library cache. */
    fun cachedEditablePlaylists(): List<PlaylistEntity> =
        editablePlaylistsFromState(_playlists.value.currentUserId)

    private fun applyPlaylistPickerInitialState(trackUri: String) {
        val cachedPlaylists = cachedEditablePlaylists()
        _playlistPicker.value = PlaylistPickerState(
            trackUri = trackUri,
            loading = cachedPlaylists.isEmpty(),
            playlists = cachedPlaylists,
        )
    }

    private suspend fun loadPlaylistPickerMembership(trackUri: String, gen: Int) {
        var userId = _playlists.value.currentUserId
        var playlists = cachedEditablePlaylists()

        if (playlists.isEmpty()) {
            if (userId == null) {
                userId = runCatching { controller.currentUserId() }.getOrNull()
            }
            runCatching { controller.refreshPlaylists() }
            playlists = runCatching { controller.editablePlaylists(userId) }
                .getOrElse { emptyList() }
            if (gen != playlistPickerLoadGen) return
            _playlistPicker.update { current ->
                if (current.trackUri != trackUri) return@update current
                current.copy(playlists = playlists, loading = playlists.isEmpty())
            }
        }

        if (gen != playlistPickerLoadGen) return

        val isInLikedSongs = runCatching { controller.isTrackSaved(trackUri) }
            .getOrDefault(false)

        val containing = if (playlists.isEmpty()) {
            emptySet()
        } else {
            runCatching {
                controller.playlistsContainingTrack(
                    trackUri,
                    playlists.map { it.playlist_id },
                )
            }.getOrDefault(emptySet())
        }

        if (gen != playlistPickerLoadGen) return
        _playlistPicker.update { current ->
            if (current.trackUri != trackUri) return@update current
            current.copy(
                loading = false,
                playlists = playlists,
                containingPlaylistIds = containing,
                isInLikedSongs = isInLikedSongs,
                likedSongsSelected = isInLikedSongs,
            )
        }
    }

    private fun editablePlaylistsFromState(userId: String?): List<PlaylistEntity> {
        if (userId == null) return emptyList()
        return _playlists.value.items.filter { playlist ->
            playlist.owner_id == userId || playlist.is_collaborative
        }
    }

    fun togglePlaylistPickerLikedSongs() {
        _playlistPicker.update { state ->
            if (state.adding) return@update state
            state.copy(
                likedSongsSelected = !state.likedSongsSelected,
                error = null,
                statusMessage = null,
            )
        }
    }

    fun togglePlaylistPickerSelection(playlistId: String) {
        _playlistPicker.update { state ->
            if (state.adding || playlistId in state.containingPlaylistIds) return@update state
            val next = state.selectedPlaylistIds.toMutableSet()
            if (playlistId in next) next.remove(playlistId) else next.add(playlistId)
            state.copy(selectedPlaylistIds = next, error = null, statusMessage = null)
        }
    }

    fun applyPlaylistPickerChanges(onDone: () -> Unit) {
        val state = _playlistPicker.value
        val uri = state.trackUri
        if (uri.isBlank()) return
        if (!state.hasPendingChanges) {
            onDone()
            return
        }
        val likedChanged = state.likedSongsSelected != state.isInLikedSongs
        val playlistIds = state.selectedPlaylistIds.toList()
        val playlistsById = state.playlists.associateBy { it.playlist_id }
        viewModelScope.launch {
            _playlistPicker.update { it.copy(adding = true, error = null, statusMessage = null) }
            var likedError: Throwable? = null
            if (likedChanged) {
                likedError = runCatching {
                    if (state.likedSongsSelected) controller.saveTrack(uri)
                    else controller.removeTrack(uri)
                }.exceptionOrNull()
            }
            val addResults = playlistIds.map { playlistId ->
                playlistId to runCatching {
                    controller.addTrackToPlaylist(
                        playlistId = playlistId,
                        uri = uri,
                        snapshotId = playlistsById[playlistId]?.snapshot_id,
                    )
                }
            }
            val succeeded = addResults.filter { it.second.isSuccess }.map { it.first }.toSet()
            val failed = addResults.filter { it.second.isFailure }
            if (likedError == null && failed.isEmpty()) {
                if (playback.value.currentUri == uri) {
                    _playingExtras.update {
                        it.copy(isTrackSaved = state.likedSongsSelected, saveError = null)
                    }
                    playingExtrasLoadedForUri = uri
                }
                for (playlistId in succeeded) {
                    val openDetail = _playlistDetail.value
                    if (openDetail.requestedId == playlistId) {
                        val meta = runCatching { controller.trackMetadataForUri(uri) }.getOrNull()
                        if (meta != null) {
                            val track = runCatching {
                                com.lightphone.spotify.data.SpotifyTrack(
                                    id = meta.uri.substringAfterLast(':'),
                                    name = meta.title,
                                    uri = meta.uri,
                                    durationMs = meta.durationMs,
                                    artists = meta.artists.split(" · ").filter { it.isNotBlank() }
                                        .map { com.lightphone.spotify.data.SpotifyArtist(name = it) },
                                    album = com.lightphone.spotify.data.SpotifyAlbumSimple(
                                        id = meta.albumId.orEmpty(),
                                        name = meta.album,
                                        images = meta.artUrl?.let {
                                            listOf(com.lightphone.spotify.data.SpotifyImage(url = it))
                                        } ?: emptyList(),
                                    ),
                                )
                            }.getOrNull()
                            if (track != null) {
                                _playlistDetail.update { detail ->
                                    detail.copy(
                                        tracks = detail.tracks + PlaylistDetailTrackRow(
                                            track = track,
                                            addedAt = null,
                                            uri = meta.uri,
                                        ),
                                        snapshotId = addResults.firstOrNull { it.first == playlistId }
                                            ?.second?.getOrNull() ?: detail.snapshotId,
                                    )
                                }
                            }
                        }
                    }
                }
                _playlistPicker.update {
                    it.copy(
                        adding = false,
                        isInLikedSongs = state.likedSongsSelected,
                        likedSongsSelected = state.likedSongsSelected,
                        selectedPlaylistIds = emptySet(),
                        containingPlaylistIds = it.containingPlaylistIds + succeeded,
                        statusMessage = "Saved",
                    )
                }
                onDone()
            } else {
                val messages = buildList {
                    likedError?.message?.let { add(it) }
                    failed.forEach { (id, result) ->
                        val name = playlistsById[id]?.name ?: id
                        add("$name: ${result.exceptionOrNull()?.message ?: "failed"}")
                    }
                }
                val partial = succeeded.isNotEmpty() || (likedChanged && likedError == null)
                _playlistPicker.update {
                    it.copy(
                        adding = false,
                        isInLikedSongs = if (likedError == null && likedChanged) {
                            state.likedSongsSelected
                        } else {
                            it.isInLikedSongs
                        },
                        likedSongsSelected = if (likedError == null && likedChanged) {
                            state.likedSongsSelected
                        } else {
                            it.likedSongsSelected
                        },
                        selectedPlaylistIds = if (partial) {
                            state.selectedPlaylistIds - succeeded
                        } else {
                            state.selectedPlaylistIds
                        },
                        containingPlaylistIds = it.containingPlaylistIds + succeeded,
                        error = messages.joinToString("\n"),
                        statusMessage = if (partial) "Partially saved" else null,
                    )
                }
            }
        }
    }

    fun addTrackToSelectedPlaylists(onAdded: () -> Unit) = applyPlaylistPickerChanges(onAdded)

    fun loadAlbumDetail(albumId: String) {
        _albumDetail.value = AlbumDetailState(loading = true, requestedId = albumId)
        viewModelScope.launch {
            val cachedSaved = runCatching { controller.isSavedAlbumCached(albumId) }
                .getOrDefault(false)
            _albumDetail.update {
                if (it.requestedId != albumId) return@update it
                it.copy(isSaved = cachedSaved, isSavedConfirmed = false)
            }
            runCatching { controller.albumDetail(albumId) }
                .onSuccess { result ->
                    if (_albumDetail.value.requestedId != albumId) return@onSuccess
                    _albumDetail.value = AlbumDetailState(
                        requestedId = albumId,
                        album = result.album,
                        isSaved = result.isSaved,
                        isSavedConfirmed = true,
                    )
                }
                .onFailure { e ->
                    if (_albumDetail.value.requestedId != albumId) return@onFailure
                    _albumDetail.value = AlbumDetailState(
                        requestedId = albumId,
                        error = e.message ?: "Could not load album",
                        isSaved = cachedSaved,
                    )
                }
        }
    }

    fun toggleAlbumSave(albumId: String) {
        viewModelScope.launch {
            val current = _albumDetail.value
            if (current.requestedId != albumId || current.saving) return@launch
            _albumDetail.update { if (it.requestedId == albumId) it.copy(saving = true) else it }
            val result = runCatching {
                if (current.isSaved) controller.removeAlbum(albumId) else controller.saveAlbum(albumId)
            }
            // Re-read the latest state before writing: the user may have navigated to a
            // different album while the save/remove call was in flight.
            _albumDetail.update { latest ->
                if (latest.requestedId != albumId) return@update latest
                latest.copy(
                    saving = false,
                    isSaved = if (result.isSuccess) !current.isSaved else latest.isSaved,
                    error = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    fun loadArtistDetail(artistId: String) {
        _artistDetail.value = ArtistDetailState(loading = true, requestedId = artistId)
        viewModelScope.launch {
            runCatching { controller.artistDetail(artistId) }
                .onSuccess { result ->
                    if (_artistDetail.value.requestedId != artistId) return@onSuccess
                    _artistDetail.value = ArtistDetailState(
                        requestedId = artistId,
                        artist = result.artist,
                        topTracks = result.topTracks,
                        albums = result.albums,
                    )
                }
                .onFailure { e ->
                    if (_artistDetail.value.requestedId != artistId) return@onFailure
                    _artistDetail.value = ArtistDetailState(
                        requestedId = artistId,
                        error = e.message ?: "Could not load artist",
                    )
                }
        }
    }

    private var searchJob: Job? = null

    fun updateSearchQuery(query: String) {
        _search.value = _search.value.copy(query = query)
    }

    fun submitSearch(query: String) {
        if (query.isBlank()) return
        val trimmed = query.trim()
        if (!isNetworkOnline()) {
            _search.update {
                it.copy(
                    query = trimmed,
                    error = null,
                    refreshError = null,
                    initialLoading = false,
                    refreshing = false,
                )
            }
            return
        }
        val requestId = ++searchRequestId
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val prev = _search.value
            val sameQuery = prev.resultsQuery == trimmed && prev.results != null
            _search.update {
                it.copy(
                    query = trimmed,
                    results = if (sameQuery) it.results else null,
                    resultsQuery = if (sameQuery) it.resultsQuery else null,
                    initialLoading = !sameQuery,
                    refreshing = sameQuery,
                    error = null,
                    refreshError = null,
                    filter = if (it.query != trimmed) SearchFilter.All else it.filter,
                )
            }
            try {
                val results = withTimeout(SEARCH_TIMEOUT_MS) { controller.search(trimmed) }
                if (requestId != searchRequestId) return@launch
                _search.update {
                    it.copy(
                        query = trimmed,
                        resultsQuery = trimmed,
                        results = results,
                        error = null,
                        refreshError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (requestId != searchRequestId) return@launch
                if (!isNetworkOnline()) {
                    _search.update {
                        it.copy(error = null, refreshError = null, initialLoading = false, refreshing = false)
                    }
                    return@launch
                }
                val message = when (e) {
                    is TimeoutCancellationException -> "Search timed out — try again."
                    else -> e.message ?: "Search failed"
                }
                _search.update { s ->
                    val keep = s.resultsQuery == trimmed && s.results != null
                    if (keep) {
                        s.copy(refreshError = message, error = null)
                    } else {
                        s.copy(error = message, refreshError = null)
                    }
                }
            } finally {
                if (requestId == searchRequestId) {
                    _search.update { it.copy(initialLoading = false, refreshing = false) }
                }
            }
        }
    }

    fun setSearchFilter(filter: SearchFilter) {
        _search.value = _search.value.copy(filter = filter)
    }

    /**
     * Start a queue.
     *
     * Every Spotify play funnels through here, which makes it the one place radio has to be told to
     * stop — two players sharing the speakers is the failure mode worth being deliberate about, and it
     * is not left to audio focus to sort out.
     *
     * It is also the one place an **episode's saved position** can be applied so that every route
     * gets it: the show screen, Saved Episodes, and Downloads. Downloads previously had no resume at
     * all — it calls `playTracks` directly — so a downloaded episode always restarted from zero by
     * construction, whatever `playEpisode` did.
     *
     * The position is passed down into the load rather than applied by a follow-up seek; see
     * [PlaybackController.play]. [startPositionMs] lets a caller override (0 to force a restart).
     */
    fun playTracks(
        tracks: List<TrackMetadata>,
        startIndex: Int,
        contextLabel: String? = null,
        startPositionMs: Long? = null,
    ) {
        if (tracks.isEmpty()) return
        radioController.stop()
        controller.ensureServiceStarted()
        val start = startPositionMs ?: tracks.getOrNull(startIndex)
            ?.uri
            ?.takeIf { it.isEpisodeUri() }
            ?.let { podcastPreferences.resumePosition(it) }
            ?: 0L
        controller.play(tracks, startIndex, contextLabel, start)
    }

    fun playLikedFrom(index: Int) {
        viewModelScope.launch {
            val tracks = runCatching { controller.likedTracksForPlayback(index) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0, "Liked Songs")
            }
        }
    }

    fun playAlbumFrom(albumId: String, trackIndex: Int) {
        viewModelScope.launch {
            val detail = _albumDetail.value.album
            val tracks = detail?.tracks?.items?.map { it.toMetadata() }
                ?: runCatching { controller.albumTracks(albumId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                val label = detail?.name ?: tracks.firstOrNull()?.album
                playTracks(tracks, trackIndex.coerceIn(0, tracks.lastIndex), label)
            }
        }
    }

    fun playSearchTrack(item: SearchResultItem.Track) {
        val meta = item.track.toMetadata()
        playTracks(listOf(meta), 0, meta.album.ifBlank { null })
    }

    fun playSearchPlaylist(playlistId: String, playlistName: String? = null, onStarted: () -> Unit = {}) {
        viewModelScope.launch {
            val tracks = runCatching { controller.playlistTracks(playlistId) }.getOrNull()
            if (!tracks.isNullOrEmpty()) {
                playTracks(tracks, 0, playlistName)
                onStarted()
            }
        }
    }

    fun openSearchResult(
        item: SearchResultItem,
        onOpenAlbum: (String, String) -> Unit,
        onOpenArtist: (String) -> Unit,
        onPlayTrack: (SearchResultItem.Track) -> Unit,
        onOpenPlaylist: (String, String) -> Unit,
        onOpenShow: (String, String) -> Unit,
    ) {
        when (item) {
            is SearchResultItem.Track -> onPlayTrack(item)
            is SearchResultItem.Album -> onOpenAlbum(item.album.id, item.album.name)
            is SearchResultItem.Artist -> onOpenArtist(item.artist.id)
            is SearchResultItem.Playlist -> onOpenPlaylist(item.playlist.id, item.playlist.name)
            // A show opens its episode list, the same destination the Podcasts tab uses, so a show
            // found by search behaves like one you had already followed.
            is SearchResultItem.Show -> onOpenShow(item.show.id, item.show.name)
        }
    }

    fun playArtistTopTrack(index: Int) {
        val tracks = _artistDetail.value.topTracks.map { it.toMetadata() }
        val label = _artistDetail.value.artist?.name
        playTracks(tracks, index, label)
    }

    private var playingExtrasLoadedForUri: String? = null
    private var playingExtrasJob: Job? = null

    fun refreshPlayingScreen() {
        val uri = playback.value.currentUri ?: return
        val state = playback.value
        if (state.title.isNullOrBlank() || state.durationMs <= 0L || state.artUrl.isNullOrBlank()) {
            controller.refreshNowPlayingFromWebApi()
        }
        if (uri == playingExtrasLoadedForUri) return
        val requestedUri = uri
        playingExtrasJob?.cancel()
        playingExtrasJob = viewModelScope.launch {
            val localHint = runCatching { controller.isLikedTrackCached(requestedUri) }
                .getOrDefault(false)
            if (playback.value.currentUri == requestedUri) {
                _playingExtras.update { it.copy(isTrackSaved = localHint, saveError = null) }
            }

            val saved = runCatching { controller.isTrackSaved(requestedUri) }
                .getOrElse { e ->
                    if (playback.value.currentUri != requestedUri) return@launch
                    _playingExtras.value = _playingExtras.value.copy(
                        saveError = e.message ?: "Could not check liked status",
                    )
                    return@launch
                }
            if (playback.value.currentUri != requestedUri) return@launch
            _playingExtras.value = _playingExtras.value.copy(isTrackSaved = saved, saveError = null)
            playingExtrasLoadedForUri = requestedUri
        }
    }

    fun saveCurrentTrack() {
        val uri = playback.value.currentUri ?: return
        viewModelScope.launch {
            val current = _playingExtras.value
            if (current.savePending || current.isTrackSaved) return@launch
            _playingExtras.value = current.copy(savePending = true, saveError = null)
            val result = runCatching { controller.saveTrack(uri) }
            // The user may have skipped to a different track while the save request
            // was in flight — onCurrentTrackChanged already reset _playingExtras for
            // it, so applying this result now would show the save state on the wrong
            // track.
            if (playback.value.currentUri != uri) return@launch
            _playingExtras.value = PlayingExtrasState(
                isTrackSaved = result.isSuccess,
                savePending = false,
                saveError = result.exceptionOrNull()?.message,
            )
            if (result.isSuccess) {
                playingExtrasLoadedForUri = uri
            }
        }
    }

    fun toggleCurrentTrackSave() {
        val uri = playback.value.currentUri ?: return
        viewModelScope.launch {
            val current = _playingExtras.value
            if (current.savePending) return@launch
            _playingExtras.value = current.copy(savePending = true, saveError = null)
            val wasSaved = current.isTrackSaved
            val result = runCatching {
                if (wasSaved) controller.removeTrack(uri) else controller.saveTrack(uri)
            }
            if (playback.value.currentUri != uri) return@launch
            _playingExtras.value = PlayingExtrasState(
                isTrackSaved = if (result.isSuccess) !wasSaved else wasSaved,
                savePending = false,
                saveError = result.exceptionOrNull()?.message,
            )
            if (result.isSuccess) {
                playingExtrasLoadedForUri = uri
            }
        }
    }

    // Transport is routed by destination: while a Spotify Connect device is active every
    // command goes over the Web API instead of to the local engine. The screens do not
    // branch on this — they call the same methods either way.

    private val isRadio: Boolean get() = radioController.state.value.isActive

    fun resume() = when {
        isRadio -> radioController.resume()
        isRemote -> connectController.play()
        // Play on a restored track means load it, not un-pause an engine holding nothing. Checked
        // before `controller.resume()` because that call would otherwise be a silent no-op — the exact
        // dead button this was meant to remove.
        controller.state.value.currentUri == null && _resumable.value != null -> resumeLastTrack()
        else -> controller.resume()
    }

    fun pause() {
        // Save before pausing: podcasts are the reason this exists, and pause is when you put the
        // phone away — which is also the last moment anything gets written before a kill.
        playback.value.let {
            rememberEpisodePosition(it.currentUri, it.positionMs, it.durationMs)
            rememberResumable(it)
        }
        when {
            isRadio -> radioController.pause()
            isRemote -> connectController.pause()
            else -> controller.pause()
        }
    }

    // Live radio has no tracks to move between. Skipping leaves radio instead, which is the only
    // sensible reading of "next" on a stream — and beats a dead button.
    fun next() = when {
        isRadio -> radioController.stop()
        isRemote -> connectController.next()
        else -> controller.next()
    }

    fun previous() = when {
        isRadio -> radioController.stop()
        isRemote -> connectController.previous()
        else -> controller.previous()
    }

    fun toggleShuffle() {
        if (isRemote) {
            connectController.setShuffle(!playback.value.shuffleEnabled)
        } else {
            controller.toggleShuffle()
        }
    }

    fun toggleRepeat() {
        if (isRemote) {
            // Same OFF -> CONTEXT -> TRACK cycle the local engine uses, so the button
            // behaves identically on a speaker.
            val next = when (playback.value.repeatMode) {
                RepeatMode.OFF -> RepeatMode.CONTEXT
                RepeatMode.CONTEXT -> RepeatMode.TRACK
                else -> RepeatMode.OFF
            }
            connectController.setRepeat(next)
        } else {
            controller.toggleRepeat()
        }
    }

    /**
     * Jump [deltaMs] from where playback is now, clamped to the track.
     *
     * Clamped rather than passed through: a negative seek is undefined at the engine, and seeking past
     * the end would either stall or skip a track depending on the backend. Reads the derived state so a
     * jump works the same on a Connect device and on a restored track that has not been loaded yet —
     * both of which [seek] already knows how to route.
     */
    fun seekBy(deltaMs: Long) {
        val state = playback.value
        val duration = state.durationMs
        // No duration means nothing is loaded and no position is meaningful; a blind seek from 0 would
        // jump to 15 seconds into a track the user has not started.
        if (duration <= 0L) return
        // One second short of the end, not the end itself: landing exactly on the duration is what ends
        // the track, and "forward 15 seconds" should never be a skip. The upper bound is floored at
        // zero because `coerceIn` throws on an inverted range, which a sub-second duration would give.
        val target = (state.positionMs + deltaMs)
            .coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        seek(target)
    }

    fun seek(positionMs: Long) {
        // Scrubbing a restored track has no engine to talk to yet, so it moves the offer instead. The
        // bar follows the drag and playback later starts from there — the alternative was a control
        // that visibly did nothing.
        if (!isRemote && !isRadio) {
            val saved = _resumable.value
            if (saved != null && controller.state.value.currentUri == null) {
                _resumable.value = saved.copy(positionMs = positionMs.coerceAtLeast(0L))
                playbackResume.savePosition(saved.track.uri, positionMs)
                // Episodes have a second store, and it is the one `playEpisode` reads. Without this,
                // nudging a restored episode in the player and then starting it from the show screen
                // would silently discard the adjustment — easy to hit now that ±15 is a button you tap
                // repeatedly rather than a drag.
                rememberEpisodePosition(saved.track.uri, positionMs, saved.track.durationMs)
                return
            }
        }
        if (isRemote) connectController.seek(positionMs) else controller.seek(positionMs)
        // Write it down now. The episode store is otherwise only written on pause, on a track change
        // and in onCleared — so scrubbing and then locking the phone or swiping the app away left the
        // old position, or none. A scrub is one deliberate action, so this is not the per-second write
        // that rememberEpisodePosition exists to avoid.
        playback.value.let { rememberEpisodePosition(it.currentUri, positionMs, it.durationMs) }
    }

    // --- Spotify Connect ----------------------------------------------------

    fun refreshDevices() = connectController.refreshDevices()

    /**
     * Receivers found on the LAN over mDNS, which the Web API never reports (see
     * [com.lightphone.spotify.playback.connect.ZeroconfDiscovery]).
     */
    private val zeroconf = ZeroconfDiscovery(app, viewModelScope)

    val lanReceivers: StateFlow<List<ZeroconfDiscovery.Receiver>> = zeroconf.receivers

    private val zeroconfClaim = ZeroconfClaim()

    private val _claimingReceiver = MutableStateFlow<String?>(null)

    /** `host:port` of the receiver being claimed, so its row can say so. */
    val claimingReceiver: StateFlow<String?> = _claimingReceiver.asStateFlow()

    private val _lanMessage = MutableStateFlow<String?>(null)

    /**
     * Why the last claim did not work, in the receiver's own terms where it gave any.
     *
     * Separate from [connect]'s error: that one is about the Web API, and a speaker refusing a login
     * is a different problem with a different fix.
     */
    val lanMessage: StateFlow<String?> = _lanMessage.asStateFlow()

    /**
     * Log the account into a receiver found on the network, then play to it.
     *
     * This is the whole point of listing LAN receivers. Until the claim existed, a speaker Spotify had
     * never been told about could only be reached by opening Spotify on another device first; now
     * tapping it hands over this phone's own stored credential, waits for Spotify to register it, and
     * transfers playback in one gesture.
     *
     * Serialised on [_claimingReceiver]: two claims at once would race for the same transfer, and the
     * user cannot usefully want both.
     */
    fun claimLanReceiver(receiver: ZeroconfDiscovery.Receiver) {
        if (_claimingReceiver.value != null) return
        val key = "${receiver.host}:${receiver.port}"
        viewModelScope.launch {
            _claimingReceiver.value = key
            _lanMessage.value = null
            try {
                val credentials = withContext(Dispatchers.IO) { StoredCredentials.load(getApplication()) }
                // The native engine's token, not the Web API one. A receiver advertising
                // `tokenType: accesstoken` hands the token straight to Spotify to open a streaming
                // session, so it has to carry the `streaming` scope — which the Rust core's keymaster
                // OAuth has and the Web API dev-app token does not. The bearer is only a fallback for
                // a phone that has not built an engine yet.
                val bearer = withContext(Dispatchers.IO) {
                    PlaybackEngineHolder.engineOrNull()
                        ?.let { engine -> runCatching { engine.accessToken() }.getOrNull() }
                        ?: controller.webApiBearerOrNull()
                }
                val outcome = zeroconfClaim.claim(
                    host = receiver.host,
                    port = receiver.port,
                    credentials = credentials,
                    accessToken = bearer,
                    controllerName = "LightPhono",
                    controllerId = ZeroconfClaim.controllerId(getApplication()),
                    preferredPath = receiver.cpath,
                )
                when (outcome) {
                    is ZeroconfClaim.Outcome.Claimed -> onReceiverClaimed(outcome.deviceId)
                    is ZeroconfClaim.Outcome.Rejected ->
                        _lanMessage.value = "${receiver.label()} refused the login: ${outcome.message}"
                    is ZeroconfClaim.Outcome.Failed -> _lanMessage.value = outcome.message
                }
            } finally {
                _claimingReceiver.value = null
            }
        }
    }

    /**
     * A claim succeeded. Wait for Spotify to list the receiver, then hand playback over.
     *
     * When it never shows up the claim is still not treated as a failure: the receiver said yes, and
     * on a slow speaker the registration lands after we have stopped watching. Saying so beats an
     * error the user cannot act on.
     */
    private suspend fun onReceiverClaimed(deviceId: String?) {
        val device = deviceId?.let { connectController.awaitDevice(it) }
        if (device != null && device.isTransferable) {
            castTo(device)
        } else if (device != null) {
            // Registered, but Spotify marks it restricted. castTo would return silently, so say it.
            _lanMessage.value = "${device.name} signed in but will not accept remote control."
        } else {
            _lanMessage.value = "Logged in. Give it a moment to appear under Spotify Connect."
            connectController.refreshDevices()
        }
    }

    fun clearLanMessage() {
        _lanMessage.value = null
    }

    /** Browsing needs the foreground, so it is tied to the picker being open, not to the app. */
    fun startLanDiscovery() = zeroconf.start()

    fun stopLanDiscovery() = zeroconf.stop()

    override fun onCleared() {
        // Leaving the app should not lose your place — in an episode, or in whatever was playing.
        playback.value.let {
            rememberEpisodePosition(it.currentUri, it.positionMs, it.durationMs)
            rememberResumable(it)
        }
        // NsdManager holds the discovery listener until it is told otherwise, and leaking one makes
        // the next discoverServices fail with a listener-already-in-use error.
        zeroconf.stop()
        // MediaPlayer would otherwise hold the stream open and keep playing with no UI attached.
        radioController.stop()
        super.onCleared()
    }

    /**
     * Output the user picked in the Output screen, or null for "follow the phone".
     *
     * Not persisted on purpose: an AudioDeviceInfo id is only meaningful while that device stays
     * connected, so restoring one across restarts would point at nothing. Forgetting it means the app
     * goes back to following the phone's own routing, which is the right default anyway.
     */
    var preferredOutputId: Int? = null
        private set

    private val pinnedPreferences = PinnedPreferences(app)

    /** Pin or unpin a playlist. Ordering is applied where the list is rendered. */
    /**
     * Give a Connect device a local name, or clear it with a blank one.
     *
     * Local only — Spotify has no rename endpoint; see [ConnectAliases].
     */
    fun setConnectAlias(deviceId: String, name: String) =
        ConnectAliases.setAlias(connectAliasPreferences, deviceId, name)

    fun toggleShowPinned(showId: String) =
        PinnedItems.toggleShowPinned(pinnedPreferences, showId)

    fun togglePlaylistPinned(playlistId: String) =
        PinnedItems.togglePinned(pinnedPreferences, playlistId)

    fun setFavouriteBluetooth(device: AudioOutputs.PairedDevice?) =
        PinnedItems.setFavouriteBluetooth(pinnedPreferences, device?.address, device?.name)

    /**
     * Long-press on the player's cast control: connect straight to the favourite device.
     *
     * Returns false when there is no favourite yet, which the caller turns into "go pick one" rather
     * than a silent no-op — a long-press that does nothing is indistinguishable from a missed press.
     */
    fun connectFavouriteBluetooth(): Boolean {
        val address = PinnedItems.favouriteBluetooth ?: return false
        val name = PinnedItems.favouriteBluetoothName ?: address
        connectBluetooth(AudioOutputs.PairedDevice(name = name, address = address))
        return true
    }

    fun setPreferredOutput(device: android.media.AudioDeviceInfo?) {
        preferredOutputId = device?.id
        PhonoAudioTrackSink.setPreferredOutput(device)
    }

    /** Address currently being connected, so its row can show progress. */
    private val _connectingBluetooth = MutableStateFlow<String?>(null)
    val connectingBluetooth: StateFlow<String?> = _connectingBluetooth.asStateFlow()

    private val _bluetoothMessage = MutableStateFlow<String?>(null)
    val bluetoothMessage: StateFlow<String?> = _bluetoothMessage.asStateFlow()

    fun clearBluetoothMessage() { _bluetoothMessage.value = null }

    /**
     * Bring a paired device up and, if it arrives, start playing to it.
     *
     * The message is deliberately specific about which of BluetoothConnector's routes was taken —
     * "asked the system" and "woke it, the phone may connect it" are different promises, and a music
     * app that says "connecting…" forever when it never could is worse than one that admits it.
     */
    fun connectBluetooth(device: AudioOutputs.PairedDevice) {
        if (_connectingBluetooth.value != null) return
        _connectingBluetooth.value = device.address
        _bluetoothMessage.value = null
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = BluetoothConnector.connect(app, device.address)
            val arrived = when (result) {
                BluetoothConnector.Result.Requested,
                BluetoothConnector.Result.Pairing,
                BluetoothConnector.Result.Nudged,
                -> BluetoothConnector.awaitConnected(app, device.address)
                is BluetoothConnector.Result.Refused -> false
            }
            _bluetoothMessage.value = when {
                arrived -> null
                result is BluetoothConnector.Result.Refused -> result.reason
                result is BluetoothConnector.Result.Pairing -> "Pairing ${device.name}…"
                else -> "Asked the phone to connect ${device.name}. If it stays off, wake it from the " +
                    "headphones themselves."
            }
            if (arrived) {
                // Route to it straight away: connecting headphones and then having to pick them is a
                // step nobody wants.
                AudioOutputs.snapshot(app, preferredOutputId).connected
                    .firstOrNull { it.kind == AudioOutputs.Kind.BLUETOOTH }
                    ?.let { setPreferredOutput(it.device) }
            }
            _connectingBluetooth.value = null
        }
    }

    fun clearConnectError() = connectController.clearError()

    /** Remote player state, for screens that need the device's own reading (volume). */
    val remotePlayback: StateFlow<RemotePlayback?> = connectController.remotePlayback

    /** Nudge the active Connect device's volume. Clamping lives in the controller. */
    fun setConnectVolume(percent: Int) = connectController.setVolume(percent)

    /**
     * Hand playback to [device], carrying the current queue across.
     *
     * The local queue is re-sent as an explicit uri list rather than relying on a plain
     * transfer, because after local librespot playback Spotify's own idea of "current
     * playback" is usually empty — a bare transfer would move silence.
     */
    fun castTo(device: SpotifyDevice) {
        if (!device.isTransferable) return
        val local = controller.state.value
        val queue = local.queue
        val uris = buildList {
            queue.nowPlaying?.uri?.let(::add)
            queue.nextInQueue.forEach { add(it.uri) }
            queue.nextFromContext.forEach { add(it.uri) }
        }
        connectController.transferTo(
            device = device,
            localUris = uris,
            // The now-playing track is first in the list we just built, so the remote
            // starts on it and the rest becomes the upcoming queue.
            localIndex = 0,
            localPositionMs = local.positionMs,
        )
    }

    /**
     * Pull playback back to the phone.
     *
     * Not a Connect transfer: this phone is not a Connect device (no librespot connect
     * feature), so the remote is paused and the local engine resumes from where it was.
     */
    fun returnToLocalPlayback() {
        connectController.returnToLocal()
        controller.resume()
    }
    fun addTrackToQueue(track: TrackMetadata) {
        controller.ensureServiceStarted()
        controller.addToQueue(track)
    }
    fun moveQueueItemUp(index: Int) = controller.moveQueueItemUp(index)
    fun moveQueueItemDown(index: Int) = controller.moveQueueItemDown(index)
    fun moveContextItemUp(index: Int) = controller.moveContextItemUp(index)
    fun moveContextItemDown(index: Int) = controller.moveContextItemDown(index)
    fun clearManualQueue() = controller.clearManualQueue()
    fun refreshQueue() = controller.refreshQueue()
    /**
     * Sign out, clear the backend binding, and invoke [onReadyForPicker] on the
     * main thread so the host can recreate into [com.lightphone.spotify.ui.screens.BackendPickerScreen].
     */
    fun logout(onReadyForPicker: (() -> Unit)? = null) {
        resetSessionUiState()
        controller.logout {
            com.lightphone.spotify.data.backend.BackendPreferences(getApplication()).clear()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                (getApplication() as App).clearController()
                onReadyForPicker?.invoke()
            }
        }
    }

    fun setStreamingQuality(quality: StreamingQuality) {
        _settings.value = _settings.value.copy(streamingQuality = quality)
        controller.setStreamingQuality(quality)
    }

    fun setDownloadQuality(quality: StreamingQuality) {
        // Future-only: never requeues or rewrites existing pins.
        _settings.value = _settings.value.copy(downloadQuality = quality)
        controller.setSpotifyDownloadQuality(quality)
    }

    fun setGaplessEnabled(enabled: Boolean) {
        val effective = TrackFade.effectiveGapless(enabled, TrackFadeSettings.seconds)
        _settings.value = _settings.value.copy(gaplessEnabled = effective)
        controller.setGaplessEnabled(enabled)
    }

    /**
     * How long the fade between tracks lasts, in seconds. Zero is off, which is the default.
     *
     * Setting one turns gapless on and leaves it on: the fade's two halves only meet if the seam is
     * tight, and gapless is what makes it tight. That is the whole of the rule where the two
     * settings meet, and the settings screen says it in a line rather than letting someone turn
     * both on and wonder why their album sounds wrong.
     */
    fun setTrackFadeSeconds(seconds: Int) {
        TrackFadeSettings.set(trackFadePreferences, seconds)
        if (TrackFadeSettings.enabled && !_settings.value.gaplessEnabled) {
            setGaplessEnabled(true)
        }
    }

    // --- Sleep timer --------------------------------------------------------

    /** Null when nothing with a length is playing, which is the cue to hide "end of track". */
    fun endOfItemDelayMs(): Long? = controller.endOfItemDelayMs()

    fun startSleepTimer(choice: SleepChoice) {
        SleepTimer.start(
            context = getApplication(),
            choice = choice,
            endOfItemDelayMs = controller.endOfItemDelayMs() ?: 0L,
        )
    }

    fun extendSleepTimer(minutes: Int) = SleepTimer.extend(getApplication(), minutes)

    fun cancelSleepTimer() = SleepTimer.cancel(getApplication())

    fun setNormalizationEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(normalizationEnabled = enabled)
        controller.setNormalizationEnabled(enabled)
    }

    fun setNormalizationType(type: NormalizationType) {
        _settings.value = _settings.value.copy(normalizationType = type)
        controller.setNormalizationType(type)
    }

    fun setProxy(proxy: String) {
        _settings.value = _settings.value.copy(proxy = proxy)
        val trimmed = proxy.trim()
        controller.setProxy(trimmed.ifEmpty { null })
    }

    fun toggleAdvancedSettings() {
        _settings.value = _settings.value.copy(showAdvanced = !_settings.value.showAdvanced)
    }

    fun setDarkTheme(enabled: Boolean) {
        _settings.value = _settings.value.copy(darkTheme = enabled)
        themePreferences.setDarkTheme(enabled)
    }

    /**
     * Artwork settings live in [ArtworkSettings] rather than [SettingsUiState] because
     * covers are drawn by `PhonoFallbackImage`, deep inside list rows that hold no
     * ViewModel handle.
     *
     * No cache flush is needed on change: the treatment is part of the Coil
     * transformation's `cacheKey`, so each treatment occupies its own cache entry and
     * switching back to a previous one is a cache hit.
     */
    fun setArtworkTreatment(treatment: ArtworkTreatment) {
        ArtworkSettings.setTreatment(artworkPreferences, treatment)
    }

    fun setShowNowPlayingArt(enabled: Boolean) {
        ArtworkSettings.setShowNowPlayingArt(artworkPreferences, enabled)
    }

    fun clearAudioCache() = controller.clearAudioCache()

    fun showTrackContextMenu(uri: String, id: String) {
        _contextMenu.value = ContextMenuUiState(target = ContextMenuTarget.Track(uri, id))
    }

    fun showAlbumContextMenu(albumId: String, uri: String) {
        _contextMenu.value = ContextMenuUiState(
            target = ContextMenuTarget.Album(albumId, uri),
        )
    }

    fun showPlaylistContextMenu(playlistId: String, uri: String, ownerId: String) {
        _contextMenu.value = ContextMenuUiState(
            target = ContextMenuTarget.Playlist(playlistId, uri, ownerId),
        )
    }

    fun showShowContextMenu(showId: String, uri: String) {
        _contextMenu.update { it.copy(target = ContextMenuTarget.Show(showId, uri)) }
    }

    fun dismissContextMenu() {
        _contextMenu.update { it.copy(target = null) }
    }

    fun dismissCopiedOverlay() {
        _contextMenu.update { it.copy(showCopied = false) }
    }

    fun cancelDeletePlaylist() {
        _contextMenu.update { it.copy(deleteConfirm = null) }
    }

    /**
     * Ask before wiping a collection's offline copy.
     *
     * The name comes from the downloads table rather than the caller, so every entry point — context
     * menu, detail header, Downloads screen — gets the same prompt without having to pass a label.
     */
    fun askRemoveDownload(collectionUri: String) {
        val name = downloadCollections.value.firstOrNull { it.uri == collectionUri }?.name
            ?: "this download"
        _contextMenu.update {
            it.copy(target = null, removeDownloadConfirm = RemoveDownloadConfirm(collectionUri, name))
        }
    }

    fun cancelRemoveDownload() {
        _contextMenu.update { it.copy(removeDownloadConfirm = null) }
    }

    fun confirmRemoveDownload() {
        val confirm = _contextMenu.value.removeDownloadConfirm ?: return
        _contextMenu.update { it.copy(removeDownloadConfirm = null) }
        removeDownloadCollection(confirm.collectionUri)
    }

    fun consumeNavigateToPlaylistPicker() {
        _contextMenu.update { it.copy(navigateToPlaylistPickerUri = null) }
    }

    fun contextMenuItemsFor(target: ContextMenuTarget, currentUserId: String?): List<PhonoContextMenuItem> =
        when (target) {
            is ContextMenuTarget.Track -> listOf(
                PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink),
                PhonoContextMenuItem("Add To Playlists", ContextMenuAction.AddToPlaylists),
                PhonoContextMenuItem("Remove From Library", ContextMenuAction.RemoveFromLibrary),
            )
            is ContextMenuTarget.Album -> buildList {
                add(PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink))
                add(PhonoContextMenuItem("Remove From Library", ContextMenuAction.RemoveFromLibrary))
                if (downloadsSupported) {
                    val collUri = collectionUri(
                        backendChoice, CollectionKind.Album, target.albumId, target.uri,
                    )
                    if (isCollectionDownloaded(collUri) || isCollectionDownloading(collUri)) {
                        add(PhonoContextMenuItem("Remove download", ContextMenuAction.RemoveDownload))
                    } else {
                        add(PhonoContextMenuItem("Download", ContextMenuAction.Download))
                    }
                }
            }
            is ContextMenuTarget.Show -> buildList {
                add(
                    PhonoContextMenuItem(
                        if (PinnedItems.isShowPinned(target.showId)) "Unpin" else "Pin to top",
                        ContextMenuAction.TogglePin,
                    ),
                )
                add(
                    PhonoContextMenuItem(
                        if (PodcastSettings.isAutoDownload(target.showId)) {
                            "Stop auto-downloading"
                        } else {
                            "Auto-download new episodes"
                        },
                        ContextMenuAction.ToggleAutoDownload,
                    ),
                )
                add(PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink))
                if (downloadsSupported) {
                    val collUri = collectionUri(
                        backendChoice, CollectionKind.Show, target.showId, target.uri,
                    )
                    // No plain "Download" for a show: that would mean fetching an entire back
                    // catalogue, which is the one thing a 32GB phone cannot absorb. Episodes arrive
                    // via auto-download, or one at a time from the show screen.
                    if (isCollectionDownloaded(collUri) || isCollectionDownloading(collUri)) {
                        add(PhonoContextMenuItem("Remove downloads", ContextMenuAction.RemoveDownload))
                    }
                }
            }
            is ContextMenuTarget.Playlist -> buildList {
                add(
                    PhonoContextMenuItem(
                        if (PinnedItems.isPinned(target.playlistId)) "Unpin" else "Pin to top",
                        ContextMenuAction.TogglePin,
                    ),
                )
                add(PhonoContextMenuItem("Copy Link", ContextMenuAction.CopyLink))
                if (currentUserId != null && target.ownerId == currentUserId) {
                    add(PhonoContextMenuItem("Delete Playlist", ContextMenuAction.DeletePlaylist))
                }
                if (downloadsSupported) {
                    val collUri = collectionUri(
                        backendChoice, CollectionKind.Playlist, target.playlistId, target.uri,
                    )
                    if (isCollectionDownloaded(collUri) || isCollectionDownloading(collUri)) {
                        add(PhonoContextMenuItem("Remove download", ContextMenuAction.RemoveDownload))
                    } else {
                        add(PhonoContextMenuItem("Download", ContextMenuAction.Download))
                    }
                }
            }
        }

    fun onContextMenuAction(action: ContextMenuAction) {
        val target = _contextMenu.value.target ?: return
        when (action) {
            ContextMenuAction.CopyLink -> {
                dismissContextMenu()
                copyContextMenuLink(target)
            }
            ContextMenuAction.AddToPlaylists -> {
                if (target !is ContextMenuTarget.Track) return
                dismissContextMenu()
                _contextMenu.update { it.copy(navigateToPlaylistPickerUri = target.uri) }
            }
            ContextMenuAction.RemoveFromLibrary -> {
                dismissContextMenu()
                removeContextMenuFromLibrary(target)
            }
            ContextMenuAction.TogglePin -> {
                dismissContextMenu()
                when (target) {
                    is ContextMenuTarget.Playlist -> togglePlaylistPinned(target.playlistId)
                    is ContextMenuTarget.Show -> toggleShowPinned(target.showId)
                    is ContextMenuTarget.Album, is ContextMenuTarget.Track -> Unit
                }
            }
            ContextMenuAction.ToggleAutoDownload -> {
                if (target !is ContextMenuTarget.Show) return
                dismissContextMenu()
                toggleShowAutoDownload(target.showId)
            }
            ContextMenuAction.DeletePlaylist -> {
                if (target !is ContextMenuTarget.Playlist) return
                dismissContextMenu()
                _contextMenu.update {
                    it.copy(deleteConfirm = DeletePlaylistConfirm(target.playlistId, ""))
                }
            }
            ContextMenuAction.Download -> {
                dismissContextMenu()
                when (target) {
                    is ContextMenuTarget.Album ->
                        downloadAlbumById(target.albumId, target.uri)
                    is ContextMenuTarget.Playlist ->
                        downloadPlaylistById(target.playlistId, target.uri)
                    // Neither has a whole-collection download: see contextMenuItemsFor.
                    is ContextMenuTarget.Show, is ContextMenuTarget.Track -> Unit
                }
            }
            ContextMenuAction.RemoveDownload -> {
                val uri = when (target) {
                    is ContextMenuTarget.Album -> collectionUri(
                        backendChoice, CollectionKind.Album, target.albumId, target.uri,
                    )
                    is ContextMenuTarget.Playlist -> collectionUri(
                        backendChoice, CollectionKind.Playlist, target.playlistId, target.uri,
                    )
                    is ContextMenuTarget.Show -> collectionUri(
                        backendChoice, CollectionKind.Show, target.showId, target.uri,
                    )
                    is ContextMenuTarget.Track -> null
                }
                if (uri == null) {
                    dismissContextMenu()
                } else {
                    // Swap the menu for the confirmation rather than dismissing to nothing, so the
                    // gesture reads as one continuous action.
                    askRemoveDownload(uri)
                }
            }
        }
    }

    fun confirmDeletePlaylist() {
        val confirm = _contextMenu.value.deleteConfirm ?: return
        _contextMenu.update { it.copy(deleteConfirm = null) }
        viewModelScope.launch {
            runCatching { controller.unfollowPlaylist(confirm.playlistId) }
                .onFailure { e ->
                    android.util.Log.e("Library", "deletePlaylist failed", e)
                }
        }
    }

    private fun copyContextMenuLink(target: ContextMenuTarget) {
        val url = when (target) {
            is ContextMenuTarget.Track -> spotifyShareUrl(target.uri, target.id, "track")
            is ContextMenuTarget.Album -> spotifyShareUrl(target.uri, target.albumId, "album")
            is ContextMenuTarget.Playlist -> spotifyShareUrl(target.uri, target.playlistId, "playlist")
            is ContextMenuTarget.Show -> spotifyShareUrl(target.uri, target.showId, "show")
        }
        val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Spotify link", url))
        _contextMenu.update { it.copy(showCopied = true) }
        viewModelScope.launch {
            delay(1750)
            if (_contextMenu.value.showCopied) dismissCopiedOverlay()
        }
    }

    private fun removeContextMenuFromLibrary(target: ContextMenuTarget) {
        viewModelScope.launch {
            runCatching {
                when (target) {
                    is ContextMenuTarget.Track -> controller.removeTrack(target.uri)
                    is ContextMenuTarget.Album -> controller.removeAlbum(target.albumId)
                    is ContextMenuTarget.Playlist -> controller.unfollowPlaylist(target.playlistId)
                    // Unfollowing a show needs an endpoint this fork does not call; the menu offers no
                    // "Remove From Library" for shows, so this is unreachable.
                    is ContextMenuTarget.Show -> Unit
                }
            }.onFailure { e ->
                android.util.Log.e("Library", "removeFromLibrary failed", e)
            }
        }
    }

    companion object {
        private const val SEARCH_TIMEOUT_MS = 30_000L
        private const val WARM_TIMEOUT_MS = 15_000L

        /** How long to wait for an episode to load before giving up on restoring its position. */
        private const val SEEK_WAIT_MS = 20_000L

        /**
         * How often an episode's position is committed while it plays. Ten seconds is small enough
         * that a killed process loses nothing worth noticing, and rare enough not to be flash wear.
         */
        private const val EPISODE_POSITION_SAVE_MS = 10_000L

        /** Within this of the end, an episode counts as finished rather than part-played. */
        private const val FINISHED_TAIL_MS = 60_000L

        /**
         * How many episodes "next" means. Three is roughly a commute either way, and small enough
         * that pressing it twice by mistake is not a hundred megabytes.
         */
        const val NEXT_EPISODES_COUNT = 3
        /** First-login splash: first pages + full library drain (Wi‑Fi or cellular). */
        private const val LIBRARY_BOOTSTRAP_TIMEOUT_MS = 45_000L
        private const val LOOKAHEAD_ROWS = 150
    }
}

private fun SettingsSnapshot.toUiState(
    showAdvanced: Boolean,
    darkTheme: Boolean,
    downloadQuality: StreamingQuality = StreamingQuality.HIGH,
) = SettingsUiState(
    streamingQuality = streamingQuality,
    downloadQuality = downloadQuality,
    gaplessEnabled = gaplessEnabled,
    normalizationEnabled = normalizationEnabled,
    normalizationType = normalizationType,
    proxy = proxy.orEmpty(),
    showAdvanced = showAdvanced,
    darkTheme = darkTheme,
)

/**
 * Overlays a Spotify Connect device's player state onto the local one.
 *
 * Only the fields that describe *what is playing* are replaced. Everything about the
 * session — login, Web API readiness, network, reconnect — keeps coming from the local
 * controller, because those describe this app's own connection and stay true while a
 * speaker is doing the playing.
 *
 * The queue is deliberately left as-is: `GET /me/player` returns the current item only,
 * and `/me/player/queue` is a second request per poll for something the user is unlikely
 * to be staring at during a handoff.
 */
private fun PlaybackUiState.withRemote(remote: RemotePlayback): PlaybackUiState = copy(
    currentUri = remote.uri ?: currentUri,
    title = remote.title ?: title,
    artist = remote.artist ?: artist,
    artUrl = remote.artUrl ?: artUrl,
    albumId = remote.albumId ?: albumId,
    isPlaying = remote.isPlaying,
    positionMs = remote.positionMs,
    durationMs = if (remote.durationMs > 0L) remote.durationMs else durationMs,
    shuffleEnabled = remote.shuffleEnabled,
    repeatMode = remote.repeatMode,
    // A remote device buffers on its own; showing this phone's buffering state would be
    // misleading.
    isBuffering = false,
    isLoading = false,
    statusMessage = remote.deviceName?.let { "Playing on $it" } ?: statusMessage,
)

/**
 * Draw the last-played track as a paused player.
 *
 * Only the display fields are touched. `isPlaying` stays false, which is what makes the transport show
 * a play button, and nothing here implies the engine has anything loaded — [AppViewModel.resume] is
 * where that distinction is handled.
 */
private fun PlaybackUiState.withResumable(saved: PlaybackResume.Saved): PlaybackUiState = copy(
    currentUri = saved.track.uri,
    title = saved.track.title.takeIf { it.isNotBlank() },
    artist = saved.track.artists.takeIf { it.isNotBlank() },
    artUrl = saved.track.artUrl,
    albumId = saved.track.albumId,
    positionMs = saved.positionMs,
    durationMs = saved.track.durationMs,
    isPlaying = false,
    isLoading = false,
    isBuffering = false,
)

/**
 * Overlays radio onto the playback state, so the shared Now Playing screen works for a stream without
 * knowing radio exists.
 *
 * Radio has **no duration and no position** — it is live. Both are reported as zero, which the player
 * screen already treats as "unknown": the progress bar hides itself and scrubbing is disabled, which is
 * exactly right for a stream rather than something to work around.
 *
 * `currentUri` is set to the stream's own id rather than left null, because the screen uses it as the
 * key for "is anything playing" and for restarting per-track effects.
 */
private fun PlaybackUiState.withRadio(radio: RadioUiState): PlaybackUiState = copy(
    currentUri = radio.stream?.let { "radio:${it.id}" } ?: currentUri,
    title = radio.nowPlayingTitle ?: radio.stream?.title,
    // With a now-playing title the station name is the useful second line; without one it is already
    // the first, so repeating it here would print the same string twice.
    artist = radio.stream?.let { station ->
        if (radio.nowPlayingTitle != null) station.title else station.subtitle
    },
    artUrl = radio.artworkUrl,
    // No album to open: tapping through to an album page from a radio stream goes nowhere.
    albumId = null,
    isPlaying = radio.isPlaying,
    isBuffering = radio.buffering,
    isLoading = false,
    positionMs = 0,
    durationMs = 0,
    // Shuffle and repeat are meaningless on a live stream; showing them lit would be a lie.
    shuffleEnabled = false,
    repeatMode = RepeatMode.OFF,
    statusMessage = radio.error ?: statusMessage,
)
