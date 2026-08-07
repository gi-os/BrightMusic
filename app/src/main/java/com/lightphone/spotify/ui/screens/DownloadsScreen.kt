package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.lightphone.spotify.data.TrackMetadata
import com.lightphone.spotify.data.local.DownloadedCollectionWithProgress
import com.lightphone.spotify.data.local.DownloadedTrackEntity
import com.lightphone.spotify.playback.download.DownloadProgress
import com.lightphone.spotify.playback.download.DownloadStates
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@Composable
fun DownloadsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenCollection: (collectionUri: String, title: String) -> Unit,
    /** Null when hosted as a tab; set when opened from Settings, which needs a way out. */
    onBack: (() -> Unit)? = null,
) {
    val collections by vm.downloadCollections.collectAsState()
    val listState = rememberLazyListState()
    var editMode by remember { mutableStateOf(false) }
    val colors = LightThemeTokens.colors

    PhonoScreenShell(
        title = "Downloads",
        // Edit moves to the secondary-right slot when there is a back button, because
        // PhonoScreenShell gives back priority over leftIcon — leaving Edit there would make it
        // silently vanish rather than share the bar.
        hideBackButton = onBack == null,
        onBack = { if (editMode) editMode = false else onBack?.invoke() },
        leftIcon = if (onBack == null) {
            if (editMode) Icons.Default.Check else Icons.Default.Edit
        } else {
            null
        },
        onLeftIconClick = if (onBack == null) {
            { editMode = !editMode }
        } else {
            null
        },
        secondaryRightLightIcon = if (onBack != null) {
            if (editMode) LightIcons.ACCEPT else LightIcons.PENCIL
        } else {
            null
        },
        onSecondaryRightIconClick = if (onBack != null) {
            { editMode = !editMode }
        } else {
            null
        },
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
                collections.isEmpty() -> EmptyListMessage(
                    "No downloads yet.\nDownload an album or playlist from its detail screen.",
                )
                else -> CustomScrollView(state = listState) {
                    items(collections, key = { it.uri }) { row ->
                        Column {
                            PhonoMediaListItem(
                                primaryText = row.name,
                                secondaryText = collectionSubtitle(row),
                                // Downloads used to be the one list in the app with no artwork, which
                                // made it the hardest to scan — the covers are already on disk beside
                                // the audio, so there was nothing to fetch and nothing to save.
                                showImage = true,
                                imageUrl = row.art_url,
                                placeholderIcon = collectionPlaceholder(row.type),
                                onEditDelete = if (editMode) {
                                    { vm.askRemoveDownload(row.uri) }
                                } else {
                                    null
                                },
                                onClick = {
                                    if (!editMode) onOpenCollection(row.uri, row.name)
                                },
                            )
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                    if (editMode) {
                        item {
                            LightText(
                                text = "Tap Cancel to remove a download.",
                                variant = LightTextVariant.Micro,
                                color = colors.content.copy(alpha = 0.55f),
                                modifier = Modifier.padding(top = legacyNToGridDp(12)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadCollectionDetailScreen(
    vm: AppViewModel,
    collectionUri: String,
    title: String,
    onBack: () -> Unit,
    /**
     * The whole playable collection plus the tapped position — never a single track.
     *
     * This used to be `(TrackMetadata) -> Unit` and the shell queued `listOf(track)`, so a
     * downloaded album could not continue past the tapped song *by construction*: the queue
     * held one entry, `end_of_track` found nothing after it, and playback paused. On the
     * subway that read as "downloads don't work" — the engine-side pin fixes kept being
     * right, and the queue kept being one track long.
     */
    onPlayTrack: (List<TrackMetadata>, Int) -> Unit,
) {
    val progress by vm.downloadProgress.collectAsState()
    val tracksFlow = remember(collectionUri) { vm.observeDownloadCollectionTracks(collectionUri) }
    val tracks by tracksFlow.collectAsState()
    // Only completed rows can play; queued/failed rows stay out so the index the player
    // gets matches the queue it gets.
    val playableTracks = remember(tracks) {
        tracks.filter { it.state == DownloadStates.COMPLETED }.map { it.toTrackMetadata() }
    }
    fun playFrom(track: TrackMetadata) {
        val index = playableTracks.indexOfFirst { it.uri == track.uri }
        if (index >= 0) onPlayTrack(playableTracks, index)
    }
    val listState = rememberLazyListState()
    var editMode by remember { mutableStateOf(false) }
    val colors = LightThemeTokens.colors
    val anyFailed = tracks.any { it.state == DownloadStates.FAILED }
    // Podcast collections are namespaced `spotify:show:<id>` by the podcast downloader, which is what
    // makes the show reachable from a screen that otherwise only knows a collection uri.
    val showId = collectionUri.takeIf { it.startsWith("spotify:show:") }
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotBlank() }

    PhonoScreenShell(
        title = title,
        hideBackButton = false,
        onBack = {
            if (editMode) editMode = false else onBack()
        },
        rightIcon = if (editMode) Icons.Default.Check else Icons.Default.Edit,
        onRightIconClick = { editMode = !editMode },
        rightIconVisible = tracks.isNotEmpty(),
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
                tracks.isEmpty() -> EmptyListMessage("No tracks in this download.")
                else -> CustomScrollView(state = listState) {
                    if (!editMode && (anyFailed || showId != null)) {
                        item(key = "download-collection-actions") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = legacyNToGridDp(8)),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (anyFailed) {
                                    DownloadActionText(
                                        text = "RETRY FAILED",
                                        onClick = { vm.retryFailedDownloads(collectionUri) },
                                    )
                                }
                                if (showId != null) {
                                    // A podcast is the one collection that grows after you pin it, so
                                    // it is the one that needs a way to top itself up from here
                                    // rather than from the show screen.
                                    DownloadActionText(
                                        text = "DOWNLOAD NEXT 3",
                                        onClick = { vm.downloadNextEpisodes(showId) },
                                    )
                                }
                            }
                        }
                    }
                    items(tracks, key = { it.uri }) { row ->
                        val track = remember(row.uri) { row.toTrackMetadata() }
                        val completed = row.state == DownloadStates.COMPLETED
                        val failed = row.state == DownloadStates.FAILED
                        Column {
                            if (!editMode && completed) {
                                PhonoSwipeToActionRow(
                                    onSwipeAction = { vm.addTrackToQueue(track) },
                                ) {
                                    PhonoMediaListItem(
                                        primaryText = row.title,
                                        secondaryText = downloadTrackSubtitle(row, progress[row.uri]),
                                        showImage = true,
                                        imageUrl = row.art_url,
                                        onClick = { playFrom(track) },
                                    )
                                }
                            } else {
                                PhonoMediaListItem(
                                    primaryText = row.title,
                                    secondaryText = downloadTrackSubtitle(row, progress[row.uri]),
                                    showImage = true,
                                    imageUrl = row.art_url,
                                    onEditDelete = if (editMode) {
                                        { vm.removeDownload(track) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        when {
                                            editMode -> Unit
                                            completed -> playFrom(track)
                                            // A failed row was a dead end: three automatic attempts
                                            // and then nothing to press. Most of these are a dropped
                                            // session, and the next attempt just works.
                                            failed -> vm.retryDownload(row.uri)
                                        }
                                    },
                                )
                            }
                            Spacer(Modifier.height(legacyNToGridDp(8)))
                        }
                    }
                    item {
                        LightText(
                            text = if (editMode) {
                                "Tap Cancel to remove a track."
                            } else {
                                "Tap a finished track to play. Swipe right to queue."
                            },
                            variant = LightTextVariant.Micro,
                            color = colors.content.copy(alpha = 0.55f),
                            modifier = Modifier.padding(top = legacyNToGridDp(12)),
                        )
                    }
                }
            }
        }
    }
}

private fun collectionSubtitle(row: DownloadedCollectionWithProgress): String {
    val kind = when (row.type) {
        "playlist" -> "Playlist"
        // Podcasts have been pinnable for a while and still read "Album" here, which is what a
        // subtitle written before shows existed does.
        "show" -> "Podcast"
        else -> "Album"
    }
    val unit = if (row.type == "show") "episodes" else "songs"
    val total = row.track_count
    val done = row.completed_count
    return when {
        total == 0 -> kind
        row.failed_count > 0 && done + row.in_progress_count == 0 ->
            "$kind · Failed"
        row.in_progress_count > 0 || (done in 1 until total) ->
            "$kind · $done/$total · Downloading…"
        row.failed_count > 0 ->
            "$kind · $done/$total · ${row.failed_count} failed"
        done == total ->
            "$kind · $total $unit"
        else ->
            "$kind · $done/$total"
    }
}

private fun collectionPlaceholder(type: String): ImageVector = when (type) {
    "show" -> Icons.Default.Mic
    "playlist" -> Icons.AutoMirrored.Filled.PlaylistPlay
    else -> Icons.Default.Album
}

/** A labelled text action above a download list, matching the episode list's controls. */
@Composable
private fun DownloadActionText(text: String, onClick: () -> Unit) {
    LightText(
        text = text,
        variant = LightTextVariant.Button,
        color = LightThemeTokens.colors.content,
        maxLines = 1,
        modifier = Modifier
            .lightClickable(onClick = onClick)
            .padding(vertical = legacyNToGridDp(6)),
    )
}

/**
 * The line under a download's title.
 *
 * A percentage only appears for the track actually transferring, and only once the total size is
 * known — the first chunk has to land before there is anything to be a percentage of. Everything
 * still queued says "Queued" rather than "Downloading…", which is what it said before and which made
 * a forty-track album look like forty simultaneous transfers when the drain runs one at a time.
 */
private fun downloadTrackSubtitle(
    row: DownloadedTrackEntity,
    progress: DownloadProgress? = null,
): String {
    val artists = row.artists.ifBlank { "Unknown artist" }
    val status = when (row.state) {
        DownloadStates.COMPLETED -> null
        DownloadStates.DOWNLOADING, DownloadStates.RESTARTING -> {
            val percent = progress?.percent
            if (percent != null) "Downloading… $percent%" else "Downloading…"
        }
        DownloadStates.QUEUED -> "Queued"
        DownloadStates.FAILED -> "Failed · tap to retry"
        DownloadStates.STOPPED -> "Paused"
        DownloadStates.REMOVING -> "Removing…"
        else -> "Pending"
    }
    return if (status != null) "$artists · $status" else artists
}

private fun DownloadedTrackEntity.toTrackMetadata() = TrackMetadata(
    uri = uri,
    title = title,
    artists = artists,
    album = album,
    durationMs = duration_ms,
    artUrl = art_url,
)
