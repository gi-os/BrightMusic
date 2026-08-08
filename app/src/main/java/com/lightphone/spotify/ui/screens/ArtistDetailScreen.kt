package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.runtime.Composable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.toMetadata
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.components.PhonoSwipeToActionRow
import com.lightphone.spotify.ui.components.PhonoTrackListItem
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

@Composable
fun ArtistDetailScreen(
    vm: AppViewModel,
    artistId: String,
    onBack: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onPlayTopTrack: (Int) -> Unit,
) {
    val state by vm.artistDetail.collectAsState()
    // Survives the trip into an album and back: NavHost keeps the entry's saved state, so a
    // rememberSaveable list state comes back with the offset it had.
    val listState = rememberSaveable(artistId, saver = LazyListState.Saver) { LazyListState() }

    // Only load when this is a different artist. Reloading the one already on screen replaces
    // every item, and a LazyColumn whose keys all change scrolls itself back to the top —
    // which is what threw the position away on the way back from an album.
    LaunchedEffect(artistId) {
        if (state.artist?.id != artistId) vm.loadArtistDetail(artistId)
    }

    val artist = state.artist?.takeIf { it.id == artistId }

    PhonoScreenShell(
        title = artist?.name ?: "Artist",
        hideBackButton = false,
        onBack = onBack,
        rightIconVisible = false,
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
                state.error != null && artist == null -> EmptyListMessage(state.error!!)
                else -> CustomScrollView(state = listState) {
                    if (state.topTracks.isNotEmpty()) {
                        item("popular-header") {
                            LightText(
                                text = "Popular",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(bottom = legacyNToGridDp(8)),
                            )
                        }
                        itemsIndexed(state.topTracks, key = { _, t -> t.id }) { index, track ->
                            Column {
                                PhonoSwipeToActionRow(
                                    onSwipeAction = { vm.addTrackToQueue(track.toMetadata()) },
                                ) {
                                    PhonoTrackListItem(
                                        trackNumber = index + 1,
                                        name = track.name,
                                        artists = track.artists.joinToString { it.name },
                                        durationMs = track.durationMs,
                                        artUrl = track.album?.images?.firstOrNull()?.url,
                                        onClick = { onPlayTopTrack(index) },
                                        onLongClick = {
                                            vm.showTrackContextMenu(track.uri, track.id)
                                        },
                                    )
                                }
                                Spacer(Modifier.height(legacyNToGridDp(8)))
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item("albums-header") {
                            LightText(
                                text = "Albums",
                                variant = LightTextVariant.Copy,
                                modifier = Modifier.padding(
                                    top = if (state.topTracks.isNotEmpty()) legacyNToGridDp(16) else legacyNToGridDp(0),
                                    bottom = legacyNToGridDp(8),
                                ),
                            )
                        }
                        itemsIndexed(state.albums, key = { _, a -> a.id }) { _, album ->
                            Column {
                                PhonoMediaListItem(
                                    primaryText = album.name,
                                    secondaryText = album.artists.joinToString { it.name },
                                    showImage = true,
                                    imageUrl = album.images.firstOrNull()?.url,
                                    placeholderIcon = Icons.Default.Album,
                                    onClick = { onOpenAlbum(album.id, album.name) },
                                    onLongClick = {
                                        vm.showAlbumContextMenu(
                                            album.id,
                                            album.uri.ifBlank {
                                                com.lightphone.spotify.data.backend.collectionUri(
                                                    vm.backendChoice,
                                                    com.lightphone.spotify.data.backend.CollectionKind.Album,
                                                    album.id,
                                                )
                                            },
                                        )
                                    },
                                )
                                Spacer(Modifier.height(legacyNToGridDp(8)))
                            }
                        }
                    }
                }
            }
        }
    }
}
