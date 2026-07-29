package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.webapi.SpotifyEpisode
import com.lightphone.spotify.podcast.PodcastSettings
import com.lightphone.spotify.podcast.ReleaseDate
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
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

    LaunchedEffect(Unit) { vm.loadSavedShows() }

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
                else -> CustomScrollView {
                    items(shows, key = { it.id }) { show ->
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
                }
            }
        }
    }
}

/**
 * Episodes of one show, newest first.
 *
 * Tapping an episode plays it, resuming where it was left; a long-press downloads that one episode.
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

    LaunchedEffect(showId) { vm.loadShowEpisodes(showId) }

    val show = state.shows.firstOrNull { it.id == showId }
    val episodes = state.episodesByShow[showId].orEmpty()
    val auto = PodcastSettings.isAutoDownload(showId)

    PhonoScreenShell(
        title = show?.name ?: fallbackTitle,
        hideBackButton = false,
        onBack = onBack,
        rightLightIcon = LightIcons.AUDIO_MESSAGE,
        onRightIconClick = onOpenPlaying,
        rightLoading = state.loading && episodes.isEmpty(),
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (auto) {
            LightText(
                text = "New episodes download automatically.",
                variant = LightTextVariant.Detail,
                color = PhonoSemanticColors.Placeholder,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = legacyNToGridDp(6)),
            )
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(bottom = legacyNToGridDp(20)),
        ) {
            when {
                episodes.isEmpty() && state.loading -> EmptyListMessage("Loading episodes…")
                episodes.isEmpty() && state.error != null -> EmptyListMessage(state.error!!)
                episodes.isEmpty() -> EmptyListMessage("No episodes found.")
                else -> CustomScrollView {
                    phonoCoverHeaderItem(
                        imageUrl = show?.detailArtUrl,
                        subtitle = show?.publisher?.takeIf { it.isNotBlank() },
                        placeholderIcon = Icons.Default.Mic,
                    )
                    items(episodes, key = { it.id }) { episode ->
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
                            onClick = {
                                if (episode.isPlayable) {
                                    vm.playEpisode(episode, show?.name)
                                    onOpenPlaying()
                                }
                            },
                            onLongClick = { vm.downloadEpisode(episode, show?.name, showId) },
                        )
                    }
                }
            }
        }
    }
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
