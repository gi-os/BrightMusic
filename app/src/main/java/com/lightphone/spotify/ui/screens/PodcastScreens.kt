package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.webapi.SpotifyEpisode
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.podcast.ReleaseDate
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.LibraryListRunway
import com.lightphone.spotify.ui.components.LibraryListScrollAnchor
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.formatDuration
import com.lightphone.spotify.ui.components.phonoCoverHeaderItem
import com.lightphone.spotify.ui.light.PinnedItems
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
) {
    val state by vm.podcasts.collectAsState()
    val shows = PinnedItems.sortPinnedShowsFirst(state.shows) { it.id }
    val listState = rememberLazyListState()

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
                            // A menu rather than a direct auto-download toggle: with pinning added
                            // there are two things a long-press could mean, and a gesture that
                            // silently does one of them is a gesture you have to remember.
                            onLongClick = { vm.showShowContextMenu(show.id, show.uri) },
                        )
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
 * turns the list into checkboxes for taking a batch offline in one go.
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
        if (auto && selected == null) {
            LightText(
                text = "New episodes download automatically.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(6)),
            )
        }

        if (episodes.isNotEmpty()) {
            EpisodeListControls(
                oldestFirst = feed?.oldestFirst == true,
                selectedCount = selected?.size,
                canSelect = vm.downloadsSupported,
                onToggleSort = { vm.toggleEpisodeSort(showId) },
                onStartSelection = vm::startEpisodeSelection,
                onCancelSelection = vm::cancelEpisodeSelection,
                onDownloadSelection = { vm.downloadSelectedEpisodes(showId) },
            )
        }

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
                    items(episodes.size, key = { episodes[it].id }) { index ->
                        val episode = episodes[index]
                        val resume = vm.episodeResumeMs(episode.uri)
                        PhonoMediaListItem(
                            primaryText = episode.name,
                            secondaryText = episode.subtitle(resume),
                            imageUrl = episode.artUrl,
                            placeholderIcon = Icons.Default.Mic,
                            showImage = true,
                            // Episodes Spotify will not stream to this account are greyed rather than
                            // hidden, so a gap in a feed is explained.
                            disabled = !episode.isPlayable,
                            selected = selected?.contains(episode.id),
                            onClick = {
                                when {
                                    !episode.isPlayable -> Unit
                                    selected != null -> vm.toggleEpisodeSelected(episode.id)
                                    else -> {
                                        vm.playEpisode(episode, show?.name)
                                        onOpenPlaying()
                                    }
                                }
                            },
                            // Long-press is the one-off download. While selecting it would compete
                            // with the checkbox, so it is off.
                            onLongClick = if (selected == null) {
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
 * The row above an episode list: sort order on the left, batch download on the right.
 *
 * Text rather than icons because both controls have to say what they will do — a sort arrow does not
 * distinguish "oldest first" from "reverse this", and there is no icon for "download six of these".
 * [selectedCount] non-null means selection mode is on.
 */
@Composable
private fun EpisodeListControls(
    oldestFirst: Boolean,
    selectedCount: Int?,
    canSelect: Boolean,
    onToggleSort: () -> Unit,
    onStartSelection: () -> Unit,
    onCancelSelection: () -> Unit,
    onDownloadSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = legacyNToGridDp(8)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectedCount == null) {
            // Labelled with the order the list is in, not the order tapping would give it — the same
            // way the playlist filter chips name the state rather than the action.
            EpisodeControlText(
                text = if (oldestFirst) "OLDEST FIRST" else "NEWEST FIRST",
                onClick = onToggleSort,
            )
            if (canSelect) {
                EpisodeControlText(text = "SELECT", onClick = onStartSelection)
            }
        } else {
            EpisodeControlText(text = "CANCEL", onClick = onCancelSelection)
            LightText(
                text = "$selectedCount SELECTED",
                variant = LightTextVariant.Button,
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
private fun SpotifyEpisode.subtitle(resumeMs: Long): String {
    val released = ReleaseDate.human(releaseDate, releasePrecision)
    val progress = when {
        !isPlayable -> "Not available"
        resumeMs > 0 && durationMs > resumeMs ->
            "${formatDuration(durationMs - resumeMs)} left"
        else -> formatDuration(durationMs)
    }
    return listOfNotNull(progress, released).joinToString(" · ")
}
