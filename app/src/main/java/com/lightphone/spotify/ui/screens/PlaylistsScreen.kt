package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.PlaylistFilter
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.PhonoGridCell
import com.lightphone.spotify.ui.components.PhonoMediaListItem
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.PinnedItems
import com.lightphone.spotify.ui.light.ViewSettings
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenPlaylist: (String, String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        vm.ensurePlaylistsLoaded()
        vm.resumePlaylistsFillIfNeeded()
    }

    val state by vm.playlists.collectAsState()
    val playback by vm.playback.collectAsState()
    val networkOnline = playback.networkOnline
    // Pinned first, everything else in whatever order the library sync produced. Applied here
    // rather than in the sync so a pin takes effect immediately and survives the next refresh
    // without needing a column on the entity.
    val displayItems = PinnedItems.sortPinnedFirst(state.displayItems) { it.playlist_id }
    val listState = rememberLazyListState()

    // The "New playlist" row is item 0 of the list and the list starts scrolled past it, so
    // it appears only when you deliberately scroll up at the top — the top-bar slot it used
    // to occupy is the search button now. One initial jump per content arrival + filter
    // switch; after that the position is the user's.
    val hasAddRow = networkOnline
    var addRowHidden by remember { mutableStateOf(false) }
    LaunchedEffect(state.filter) {
        listState.scrollToItem(if (hasAddRow) 1 else 0)
    }
    LaunchedEffect(displayItems.isNotEmpty(), hasAddRow) {
        if (!addRowHidden && hasAddRow && displayItems.isNotEmpty()) {
            listState.scrollToItem(1)
            addRowHidden = true
        }
    }

    PhonoScreenShell(
        hideBackButton = true,
        leftIcon = Icons.Default.Search,
        onLeftIconClick = onOpenGlobalSearch,
        rightLightIcon = LightIcons.ELLIPSES,
        onRightIconClick = onOpenOptions,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
        titleContent = {
            PlaylistFilterChips(
                selected = state.filter,
                onSelect = vm::setPlaylistsFilter,
            )
        },
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshPlaylists() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                !networkOnline && displayItems.isEmpty() && state.items.isEmpty() ->
                    EmptyListMessage("You're offline.")
                state.error != null && displayItems.isEmpty() && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && displayItems.isEmpty() ->
                    EmptyListMessage("Loading playlists…")
                state.isEmpty ->
                    EmptyListMessage(
                        if (state.filter == PlaylistFilter.YourPlaylists) {
                            "No playlists created by you."
                        } else {
                            "No playlists found."
                        },
                    )
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.items.isNotEmpty() && networkOnline) {
                        LibraryPartialSyncBanner(state.error!!)
                    }
                    val grid = ViewSettings.playlistGrid
                    val addRow: (@Composable () -> Unit)? = if (hasAddRow) {
                        {
                            PhonoMediaListItem(
                                primaryText = "New playlist",
                                secondaryText = "Create a playlist",
                                showImage = true,
                                placeholderIcon = Icons.Default.Add,
                                onClick = onCreatePlaylist,
                            )
                        }
                    } else {
                        null
                    }
                    if (grid) {
                        val rows = displayItems.chunked(2)
                        LibraryInfiniteList(
                            listState = listState,
                            items = rows,
                            remoteTotal = (state.displayRemoteTotal + 1) / 2,
                            hasMore = state.hasMore,
                            appending = state.appending,
                            canLoadMore = state.canLoadMore,
                            itemKey = { it.first().playlist_id },
                            onEnsureBufferAhead = { lastVisibleRow ->
                                vm.ensurePlaylistsBufferAhead(lastVisibleRow * 2 + 1)
                            },
                            headerContent = addRow,
                        ) { _, pair ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(12)),
                            ) {
                                pair.forEach { playlist ->
                                    val collUri = playlist.uri.ifBlank {
                                        com.lightphone.spotify.data.backend.collectionUri(
                                            vm.backendChoice,
                                            com.lightphone.spotify.data.backend.CollectionKind.Playlist,
                                            playlist.playlist_id,
                                        )
                                    }
                                    val disabled = !networkOnline && !vm.isCollectionDownloaded(collUri)
                                    val pinned = PinnedItems.isPinned(playlist.playlist_id)
                                    PhonoGridCell(
                                        name = playlist.name,
                                        subtitle = playlist.owner_name.ifBlank { playlist.owner_id }
                                            .let { if (pinned) "Pinned · $it" else it },
                                        artUrl = playlist.art_url,
                                        disabled = disabled,
                                        onClick = {
                                            if (!disabled) {
                                                onOpenPlaylist(playlist.playlist_id, playlist.name)
                                            }
                                        },
                                        onLongClick = {
                                            if (!disabled) {
                                                vm.showPlaylistContextMenu(
                                                    playlistId = playlist.playlist_id,
                                                    uri = collUri,
                                                    ownerId = playlist.owner_id,
                                                )
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
                    } else {
                        LibraryInfiniteList(
                        listState = listState,
                        items = displayItems,
                        remoteTotal = state.displayRemoteTotal,
                        hasMore = state.hasMore,
                        appending = state.appending,
                        canLoadMore = state.canLoadMore,
                        itemKey = { it.playlist_id },
                        onEnsureBufferAhead = vm::ensurePlaylistsBufferAhead,
                        headerContent = addRow,
                    ) { _, playlist ->
                        val collUri = playlist.uri.ifBlank {
                            com.lightphone.spotify.data.backend.collectionUri(
                                vm.backendChoice,
                                com.lightphone.spotify.data.backend.CollectionKind.Playlist,
                                playlist.playlist_id,
                            )
                        }
                        val disabled = !networkOnline && !vm.isCollectionDownloaded(collUri)
                        val pinned = PinnedItems.isPinned(playlist.playlist_id)
                        PhonoMediaListItem(
                            primaryText = playlist.name,
                            // The pin is marked on the subtitle rather than the title so the title
                            // stays the playlist's own name at a glance.
                            secondaryText = playlist.owner_name.ifBlank { playlist.owner_id }
                                .let { if (pinned) "Pinned · $it" else it },
                            imageUrl = playlist.art_url,
                            showImage = true,
                            placeholderIcon = Icons.AutoMirrored.Filled.PlaylistPlay,
                            disabled = disabled,
                            onClick = {
                                if (!disabled) onOpenPlaylist(playlist.playlist_id, playlist.name)
                            },
                            onLongClick = {
                                if (!disabled) {
                                    vm.showPlaylistContextMenu(
                                        playlistId = playlist.playlist_id,
                                        uri = collUri,
                                        ownerId = playlist.owner_id,
                                    )
                                }
                            },
                        )
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistFilterChips(
    selected: PlaylistFilter,
    onSelect: (PlaylistFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(8))) {
        PlaylistFilter.entries.forEach { filter ->
            PlaylistFilterChip(
                filter = filter,
                active = filter == selected,
                onSelect = onSelect,
            )
        }
    }
}

@Composable
private fun PlaylistFilterChip(
    filter: PlaylistFilter,
    active: Boolean,
    onSelect: (PlaylistFilter) -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (active) colors.content else PhonoSemanticColors.PlaceholderBg)
            .lightClickable { onSelect(filter) }
            .padding(horizontal = legacyNToGridDp(12), vertical = legacyNToGridDp(6)),
    ) {
        LightText(
            text = filter.label,
            variant = LightTextVariant.Copy,
            color = if (active) colors.background else colors.content,
            maxLines = 1,
        )
    }
}
