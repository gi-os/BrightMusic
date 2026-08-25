package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.webapi.SpotifyEpisode
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.podcast.Unheard
import com.lightphone.spotify.podcast.ReleaseDate
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.LibraryListRunway
import com.lightphone.spotify.ui.components.LibraryListScrollAnchor
import com.lightphone.spotify.ui.components.PhonoGridCell
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.formatDuration
import com.lightphone.spotify.ui.components.phonoCoverHeaderItem
import com.lightphone.spotify.ui.light.PinnedItems
import com.lightphone.spotify.ui.light.ViewSettings
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * How close to the loaded edge to start fetching the next page.
 *
 * Much smaller than the library lists' 60, which is tuned for Room: these pages come off the network
 * fifty at a time, and a distance larger than a page means the screen fetches a second page before
 * anyone has scrolled at all.
 */
private const val PODCAST_PREFETCH_DISTANCE = 12

/**
 * Saved podcast shows.
 *
 * Browsing is online-only and that is deliberate: you subscribe and enable auto-download at home, and
 * what matters on a train is the audio, which by then is already on the phone and reachable from
 * Downloads. That choice is what lets podcasts ship with no database change — see
 * [com.lightphone.spotify.podcast.PodcastSettings].
 */
@Composable
fun PodcastsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenShow: (showId: String, name: String) -> Unit,
    onOpenSavedEpisodes: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
) {
    val state by vm.podcasts.collectAsState()
    val shows = PinnedItems.sortPinnedShowsFirst(state.shows) { it.id }
    val listState = rememberLazyListState()
    // Which shows have something waiting. Recorded by the daily probe, not fetched here: see
    // AppViewModel.unheardShows.
    val unheard by vm.unheardShows.collectAsState()

    LaunchedEffect(Unit) { vm.loadSavedShows() }

    LibraryListScrollAnchor(
        listState = listState,
        loadedItemCount = shows.size,
        canLoadMore = state.showsHasMore,
        onEnsureBufferAhead = vm::ensureShowsBufferAhead,
        prefetchDistance = PODCAST_PREFETCH_DISTANCE,
    )

    PhonoScreenShell(
        title = "Podcasts",
        hideBackButton = true,
        leftIcon = Icons.Default.Search,
        onLeftIconClick = onOpenGlobalSearch,
        rightLightIcon = LightIcons.ELLIPSES,
        onRightIconClick = onOpenOptions,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                state.shows.isEmpty() && state.loading -> EmptyListMessage("Loading…")
                state.shows.isEmpty() && state.error != null -> EmptyListMessage(state.error!!)
                state.shows.isEmpty() -> EmptyListMessage(
                    "No podcasts yet. Follow a show in Spotify and it will appear here.",
                )
                else -> CustomScrollView(
                    state = listState,
                    loadedItemCount = shows.size,
                    // The scrollbar sizes itself against everything the account has followed rather
                    // than the page that happens to be loaded, so it does not shrink as you scroll.
                    virtualItemCount = state.showsTotal.takeIf { it > 0 },
                    hasMoreItems = state.showsHasMore,
                ) {
                    item(key = "saved-episodes") {
                        Column {
                            PhonoMediaListItem(
                                primaryText = "Saved Episodes",
                                secondaryText = "Episodes you saved",
                                placeholderIcon = Icons.Default.Bookmark,
                                showImage = true,
                                onClick = onOpenSavedEpisodes,
                            )
                            // A row and a grid of covers have no shared edge to separate them,
                            // so in grid mode this read as the first tile of the grid.
                            Spacer(Modifier.height(legacyNToGridDp(if (ViewSettings.podcastGrid) 18 else 8)))
                        }
                    }
                    if (ViewSettings.podcastGrid) {
                        val rows = shows.chunked(2)
                        items(rows.size, key = { rows[it].first().id }) { index ->
                            val pair = rows[index]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = legacyNToGridDp(10)),
                                horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(12)),
                            ) {
                                pair.forEach { show ->
                                    val auto = PodcastSettings.isAutoDownload(show.id)
                                    val pinned = PinnedItems.isShowPinned(show.id)
                                    PhonoGridCell(
                                        name = show.name,
                                        subtitle = listOfNotNull(
                                            "Pinned".takeIf { pinned },
                                            "Auto".takeIf { auto },
                                            show.publisher.takeIf { it.isNotBlank() },
                                        ).joinToString(" · ").takeIf { it.isNotBlank() },
                                        artUrl = show.listArtUrl,
                                        disabled = false,
                                        onClick = { onOpenShow(show.id, show.name) },
                                        onLongClick = { vm.showShowContextMenu(show.id, show.uri) },
                                        placeholderIcon = Icons.Default.Mic,
                                        dot = show.id in unheard,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    } else {
                        items(shows.size, key = { shows[it].id }) { index ->
                        val show = shows[index]
                        val auto = PodcastSettings.isAutoDownload(show.id)
                        val pinned = PinnedItems.isShowPinned(show.id)
                        PhonoMediaListItem(
                            primaryText = show.name,
                            secondaryText = listOfNotNull(
                                "Pinned".takeIf { pinned },
                                "Auto-download on".takeIf { auto },
                                show.publisher.takeIf { it.isNotBlank() },
                            ).joinToString(" · ").takeIf { it.isNotBlank() },
                            imageUrl = show.listArtUrl,
                            placeholderIcon = Icons.Default.Mic,
                            showImage = true,
                            onClick = { onOpenShow(show.id, show.name) },
                            // The newest episode of this show is one you have not started. What the
                            // dot cannot say is how many, because the phone only probes the newest.
                            trailingDot = show.id in unheard,
                            // A menu rather than a direct auto-download toggle: with pinning added
                            // there are two things a long-press could mean, and a gesture that
                            // silently does one of them is a gesture you have to remember.
                            onLongClick = { vm.showShowContextMenu(show.id, show.uri) },
                        )
                    }
                    }
                    if (state.showsHasMore) {
                        item(key = "podcast-shows-runway") {
                            LibraryListRunway(appending = state.showsAppending)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Episodes of one show.
 *
 * The whole feed is reachable, a page at a time, in either direction. Newest-first is the default and
 * what a follower of a running show wants; oldest-first is for the other kind of podcast, the one
 * with a beginning — and it is not a local re-sort, because only part of the feed is on the phone.
 * The list is refetched from the far end instead; see
 * [com.lightphone.spotify.podcast.EpisodePaging].
 *
 * Tapping an episode plays it, resuming where it was left; a long-press downloads that one. SELECT
 * turns the list into checkboxes for taking a batch offline, and DOWNLOAD NEXT 3 takes the first
 * three the phone does not already have, in whatever order the list is reading.
 *
 * The controls scroll with the list rather than sitting above it. Anchored, they cost a row of
 * height on every screenful of a list that is mostly scrolling, to offer two things you press once.
 *
 * There is deliberately **no** whole-show download control here. The header used to carry a download
 * icon that turned auto-download on, which read as "download this entire show" — a back catalogue can
 * be hundreds of hours, and nothing on the screen said otherwise. Auto-download is a per-show setting
 * now reached by a long-press in the Podcasts list, where the menu can name what it does.
 */
@Composable
fun PodcastShowScreen(
    vm: AppViewModel,
    showId: String,
    fallbackTitle: String,
    onBack: () -> Unit,
    onOpenPlaying: () -> Unit,
) {
    val state by vm.podcasts.collectAsState()
    val listState = rememberLazyListState()
    val playedEpisodes by vm.playedEpisodes.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()

    LaunchedEffect(showId) { vm.loadShowEpisodes(showId) }

    val show = state.shows.firstOrNull { it.id == showId }
    val feed = state.episodesByShow[showId]
    val episodes = feed?.episodes.orEmpty()
    val feedError = feed?.error
    val hasMoreEpisodes = feed?.hasMore == true
    val auto = PodcastSettings.isAutoDownload(showId)
    val selected = state.selectedEpisodeIds

    LibraryListScrollAnchor(
        listState = listState,
        loadedItemCount = episodes.size,
        canLoadMore = hasMoreEpisodes,
        onEnsureBufferAhead = { lastVisible -> vm.ensureEpisodeBufferAhead(showId, lastVisible) },
        prefetchDistance = PODCAST_PREFETCH_DISTANCE,
    )

    // Leaving the screen must not leave selection mode armed for the next show.
    DisposableEffect(showId) {
        onDispose { vm.cancelEpisodeSelection() }
    }

    PhonoScreenShell(
        title = show?.name ?: fallbackTitle,
        hideBackButton = false,
        onBack = onBack,
        rightLightIcon = LightIcons.AUDIO_MESSAGE,
        onRightIconClick = onOpenPlaying,
        rightLoading = feed?.loading == true && episodes.isEmpty(),
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                episodes.isEmpty() && feed?.loading == true -> EmptyListMessage("Loading episodes…")
                episodes.isEmpty() && feedError != null -> EmptyListMessage(feedError)
                episodes.isEmpty() -> EmptyListMessage("No episodes found.")
                else -> CustomScrollView(
                    state = listState,
                    loadedItemCount = episodes.size,
                    // Against the whole feed, so the thumb says how far through a five-year archive
                    // you actually are rather than how far through the pages fetched so far.
                    virtualItemCount = feed?.total?.takeIf { it > 0 },
                    hasMoreItems = hasMoreEpisodes,
                ) {
                    phonoCoverHeaderItem(
                        imageUrl = show?.detailArtUrl,
                        subtitle = show?.publisher?.takeIf { it.isNotBlank() },
                        placeholderIcon = Icons.Default.Mic,
                    )
                    item(key = "podcast-episode-controls") {
                        EpisodeListControls(
                            oldestFirst = feed?.oldestFirst == true,
                            selectedCount = selected?.size,
                            canDownload = vm.downloadsSupported,
                            autoDownloadOn = auto,
                            onToggleSort = { vm.toggleEpisodeSort(showId) },
                            onStartSelection = vm::startEpisodeSelection,
                            onCancelSelection = vm::cancelEpisodeSelection,
                            onDownloadSelection = { vm.downloadSelectedEpisodes(showId) },
                            onDownloadNext = { vm.downloadNextEpisodes(showId) },
                            // Anything queued or transferring for this show counts: the button
                            // fires a batch, so per-row progress is the honest signal.
                            downloading = downloadProgress.isNotEmpty(),
                        )
                    }
                    items(episodes.size, key = { episodes[it].id }) { index ->
                        val episode = episodes[index]
                        val resume = vm.episodeResumeMs(episode.uri)
                        val played = episode.uri in playedEpisodes
                        // Unplayable is either Spotify's own flag or something a failed download
                        // taught us — see SpotifyEpisode.isExternallyHosted.
                        val unhosted = episode.isExternallyHosted ||
                            PodcastSettings.isUnplayable(episode.uri)
                        val usable = episode.isStreamable && !unhosted
                        PhonoMediaListItem(
                            primaryText = episode.name,
                            secondaryText = episode.subtitle(resume, unhosted, played),
                            imageUrl = episode.artUrl,
                            placeholderIcon = Icons.Default.Mic,
                            showImage = true,
                            // Never started, and playable. A part-listened episode says "22 min
                            // left" on the line below instead — see Unheard.
                            trailingDot = Unheard.dotted(
                                played = played,
                                resumeMs = resume,
                                playable = usable,
                            ),
                            // Episodes Phono cannot play are greyed rather than hidden, so a gap in a
                            // feed is explained.
                            disabled = !usable,
                            selected = selected?.contains(episode.id),
                            onClick = {
                                when {
                                    !usable -> Unit
                                    selected != null -> vm.toggleEpisodeSelected(episode.id)
                                    else -> {
                                        vm.playEpisode(episode, show?.name)
                                        onOpenPlaying()
                                    }
                                }
                            },
                            // Long-press is the one-off download. While selecting it would compete
                            // with the checkbox, so it is off.
                            onLongClick = if (selected == null && usable) {
                                { vm.downloadEpisode(episode, show?.name, showId) }
                            } else {
                                null
                            },
                        )
                    }
                    if (hasMoreEpisodes) {
                        item(key = "podcast-episodes-runway") {
                            LibraryListRunway(appending = feed?.appending == true)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The controls that sit with the episode list: sort order, batch download, and the state of
 * auto-download.
 *
 * Text rather than icons because each control has to say what it will do — a sort arrow does not
 * distinguish "oldest first" from "reverse this", and there is no icon for "download three of these".
 * [selectedCount] non-null means selection mode is on.
 */
@Composable
private fun EpisodeListControls(
    oldestFirst: Boolean,
    selectedCount: Int?,
    canDownload: Boolean,
    autoDownloadOn: Boolean,
    onToggleSort: () -> Unit,
    onStartSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onDownloadSelection: () -> Unit,
    onDownloadNext: () -> Unit,
    downloading: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = legacyNToGridDp(8))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selectedCount == null) {
                // Labelled with the order the list is in, not the order tapping would give it — the
                // same way the playlist filter chips name the state rather than the action.
                EpisodeControlText(
                    text = if (oldestFirst) "OLDEST FIRST" else "NEWEST FIRST",
                    onClick = onToggleSort,
                )
                if (canDownload) {
                    EpisodeControlText(text = "SELECT", onClick = onStartSelection)
                }
            } else {
                EpisodeControlText(text = "CANCEL", onClick = onCancelSelection)
                // Detail rather than Button: it is a readout, not something to press, and at button
                // size three controls do not fit across the screen.
                LightText(
                    text = "$selectedCount selected",
                    variant = LightTextVariant.Detail,
                    color = PhonoSemanticColors.Placeholder,
                    maxLines = 1,
                )
                EpisodeControlText(
                    text = "DOWNLOAD",
                    onClick = onDownloadSelection,
                    enabled = selectedCount > 0,
                )
            }
        }
        if (selectedCount == null && canDownload) {
            // Tapping this used to do nothing visible: the work is a queue write and a service
            // wake, and the rows it queued are often below the fold. Saying "Downloading…" while
            // anything is in flight is the difference between a button that worked and one that
            // looked broken and got pressed again.
            EpisodeControlText(
                text = if (downloading) "DOWNLOADING…" else "DOWNLOAD NEXT 3",
                onClick = onDownloadNext,
                enabled = !downloading,
            )
        }
        if (autoDownloadOn && selectedCount == null) {
            LightText(
                text = "New episodes download automatically.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = legacyNToGridDp(4)),
            )
        }
    }
}

@Composable
private fun EpisodeControlText(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    LightText(
        text = text,
        variant = LightTextVariant.Button,
        color = if (enabled) LightThemeTokens.colors.content else PhonoSemanticColors.DisabledIcon,
        maxLines = 1,
        modifier = Modifier
            .lightClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = legacyNToGridDp(6)),
    )
}

/**
 * Duration, plus how far in you are.
 *
 * "22 min left" is the number a podcast listener wants; the total only matters before you start.
 */
private fun SpotifyEpisode.subtitle(resumeMs: Long, unhosted: Boolean, played: Boolean): String {
    val released = ReleaseDate.human(releaseDate, releasePrecision)
    val progress = when {
        // "Played" outranks the running time: on a feed you are working through, which ones you
        // have heard is the question the list exists to answer — and the resume position cannot
        // answer it, because it is cleared at the end. Finished and never-started looked alike.
        played && !unhosted && isPlayable -> "Played"
        // Two different dead ends, and worth saying which. A licensing gap may lift; audio that was
        // never on Spotify's servers is never going to play here, and "Not available" alone had
        // people retrying it.
        unhosted -> "Not on Spotify's servers"
        !isPlayable -> "Not available"
        resumeMs > 0 && durationMs > resumeMs ->
            "${formatDuration(durationMs - resumeMs)} left"
        else -> formatDuration(durationMs)
    }
    return listOfNotNull(progress, released).joinToString(" · ")
}

/**
 * The account's saved episodes.
 *
 * Episodes are saved with the same `/me/library?uris=` call tracks are, so an episode liked here shows
 * up in Spotify's own clients too — but they are deliberately kept out of the `liked_tracks` table,
 * which backs Liked Songs, so this is where they surface instead.
 */
@Composable
fun SavedEpisodesScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.savedEpisodes.collectAsState()
    val playedEpisodes by vm.playedEpisodes.collectAsState()

    LaunchedEffect(Unit) { vm.loadSavedEpisodes() }

    PhonoScreenShell(
        title = "Saved Episodes",
        hideBackButton = false,
        onBack = onBack,
        rightLightIcon = LightIcons.AUDIO_MESSAGE,
        onRightIconClick = onOpenPlaying,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                state.episodes.isEmpty() && state.loading -> EmptyListMessage("Loading…")
                state.episodes.isEmpty() && state.error != null ->
                    EmptyListMessage(state.error!!)

                state.episodes.isEmpty() -> EmptyListMessage(
                    "No saved episodes. Tap the + on an episode you are listening to and it lands here.",
                )

                else -> CustomScrollView(loadedItemCount = state.episodes.size) {
                    items(state.episodes.size, key = { state.episodes[it].id }) { index ->
                        val episode = state.episodes[index]
                        val resume = vm.episodeResumeMs(episode.uri)
                        PhonoMediaListItem(
                            primaryText = episode.name,
                            secondaryText = episode.subtitle(
                                resumeMs = resume,
                                unhosted = !episode.isStreamable,
                                played = episode.uri in playedEpisodes,
                            ),
                            imageUrl = episode.artUrl,
                            placeholderIcon = Icons.Default.Mic,
                            showImage = true,
                            trailingDot = Unheard.dotted(
                                played = episode.uri in playedEpisodes,
                                resumeMs = resume,
                                playable = episode.isStreamable,
                            ),
                            disabled = !episode.isStreamable,
                            onClick = {
                                if (!episode.isStreamable) return@PhonoMediaListItem
                                // The show name comes with the episode on this endpoint, unlike the
                                // per-show list where the screen already knows it.
                                vm.playEpisode(episode, episode.show?.name)
                                onOpenPlaying()
                            },
                            // Hold to unsave — the row is already the play action, and this is the
                            // only screen where the list itself is the thing being edited.
                            onLongClick = { vm.removeSavedEpisode(episode.uri) },
                        )
                    }
                }
            }
        }
    }
}
