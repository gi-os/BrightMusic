package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.MadeForYou
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.CustomScrollView
import com.lightphone.spotify.ui.components.PhonoGridCell
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.ViewSettings
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons

/**
 * What the "Made for you" folder holds: Spotify's own mixes, on their own page.
 *
 * Deliberately plain. It reads the playlists already loaded for the library page and applies the
 * same rule rather than fetching anything of its own — these rows are in memory by the time the
 * folder is on screen, and a screen that re-fetched them would spend a request to show the same
 * ten names. Grid or list follows the setting the library page is using, so opening the folder does
 * not change how your playlists look.
 */
@Composable
fun MadeForYouScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    val state by vm.playlists.collectAsState()
    val playback by vm.playback.collectAsState()
    val networkOnline = playback.networkOnline
    val mixes = state.displayItems.filter {
        MadeForYou.matches(it.name, it.owner_id, it.owner_name)
    }

    PhonoScreenShell(
        title = MadeForYou.TITLE,
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
            if (mixes.isEmpty()) {
                // Only reachable by going back to a library that has finished re-syncing without
                // them — the folder is not drawn at all when there is nothing to fold.
                EmptyListMessage("No mixes right now.")
            } else if (ViewSettings.playlistGrid) {
                val rows = mixes.chunked(2)
                CustomScrollView(loadedItemCount = rows.size) {
                    items(rows, key = { it.first().playlist_id }) { pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(12)),
                        ) {
                            pair.forEach { playlist ->
                                PhonoGridCell(
                                    name = playlist.name,
                                    subtitle = playlist.owner_name.ifBlank { "Spotify" },
                                    artUrl = playlist.art_url,
                                    disabled = !networkOnline,
                                    onClick = {
                                        if (networkOnline) {
                                            onOpenPlaylist(playlist.playlist_id, playlist.name)
                                        }
                                    },
                                    placeholderIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            } else {
                CustomScrollView(loadedItemCount = mixes.size) {
                    items(mixes, key = { it.playlist_id }) { playlist ->
                        PhonoMediaListItem(
                            primaryText = playlist.name,
                            secondaryText = playlist.owner_name.ifBlank { "Spotify" },
                            imageUrl = playlist.art_url,
                            showImage = true,
                            placeholderIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            disabled = !networkOnline,
                            onClick = {
                                if (networkOnline) {
                                    onOpenPlaylist(playlist.playlist_id, playlist.name)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
