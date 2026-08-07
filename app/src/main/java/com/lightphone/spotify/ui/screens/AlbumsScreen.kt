package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.components.buildLibraryDateIndex
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.components.LibraryInfiniteList
import com.lightphone.spotify.ui.components.PhonoGridCell
import com.lightphone.spotify.ui.components.ScrollbarMode
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.lightphone.spotify.ui.phono.PhonoScreenShell
import com.thelightphone.sdk.ui.LightIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    /** Songs/Albums switch, supplied by [LikedScreen]; null when shown on its own. */
    titleContent: (@Composable () -> Unit)? = null,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
) {
    LaunchedEffect(Unit) {
        vm.ensureSavedAlbumsLoaded()
        vm.resumeSavedAlbumsFillIfNeeded()
    }

    val state by vm.savedAlbums.collectAsState()
    val playback by vm.playback.collectAsState()
    val networkOnline = playback.networkOnline
    val listState = rememberLazyListState()
    // Albums draw as a 2-across cover grid, but the list machinery (paging, runway, date
    // scrub) thinks in LazyColumn items — so the grid is the list of *pairs*, one Row per
    // item. Every index that crosses the boundary converts: the scrubber and date index
    // speak row positions, the ViewModel's buffer-ahead speaks album counts.
    val albumRows = remember(state.items) { state.items.chunked(2) }
    val dateIndex = remember(albumRows) {
        buildLibraryDateIndex(albumRows) { it.first().added_at }
    }

    PhonoScreenShell(
        title = if (titleContent == null) "Albums" else null,
        titleContent = titleContent,
        hideBackButton = true,
        leftIcon = Icons.Default.Search,
        onLeftIconClick = onOpenGlobalSearch,
        rightLightIcon = LightIcons.ELLIPSES,
        onRightIconClick = onOpenOptions,
        horizontalPadding = legacyNToGridDp(20),
        modifier = Modifier.fillMaxSize(),
    ) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = { vm.refreshSavedAlbums() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                !networkOnline && state.items.isEmpty() ->
                    EmptyListMessage("You're offline.")
                state.error != null && state.items.isEmpty() ->
                    EmptyListMessage(state.error!!)
                state.initialLoading && state.items.isEmpty() ->
                    EmptyListMessage("Loading albums…")
                state.isEmpty ->
                    EmptyListMessage("No saved albums found.")
                else -> Column(Modifier.fillMaxSize()) {
                    if (state.error != null && state.items.isNotEmpty() && networkOnline) {
                        LibraryPartialSyncBanner(state.error!!)
                    }
                    LibraryInfiniteList(
                        listState = listState,
                        items = albumRows,
                        remoteTotal = (state.remoteTotal + 1) / 2,
                        hasMore = state.hasMore,
                        appending = state.appending,
                        canLoadMore = state.canLoadMore,
                        itemKey = { it.first().album_id },
                        onEnsureBufferAhead = { lastVisibleRow ->
                            vm.ensureSavedAlbumsBufferAhead(lastVisibleRow * 2 + 1)
                        },
                        dateIndex = dateIndex,
                        scrollbarMode = ScrollbarMode.ScrubHoldOnly,
                        onScrubToIndex = { index -> vm.scrollSavedAlbumsToIndex(listState, index) },
                        onScrubJumpChange = { active ->
                            if (active) vm.onScrubJumpStart() else vm.onScrubJumpEnd()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) { _, pair ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(12)),
                        ) {
                            pair.forEach { saved ->
                                val collUri = saved.uri.ifBlank {
                                    com.lightphone.spotify.data.backend.collectionUri(
                                        vm.backendChoice,
                                        com.lightphone.spotify.data.backend.CollectionKind.Album,
                                        saved.album_id,
                                    )
                                }
                                val disabled = !networkOnline && !vm.isCollectionDownloaded(collUri)
                                PhonoGridCell(
                                    name = saved.name,
                                    subtitle = saved.artist_names,
                                    artUrl = saved.art_url,
                                    disabled = disabled,
                                    onClick = {
                                        if (!disabled) onOpenAlbum(saved.album_id, saved.name)
                                    },
                                    onLongClick = {
                                        if (!disabled) {
                                            vm.showAlbumContextMenu(saved.album_id, collUri)
                                        }
                                    },
                                    placeholderIcon = Icons.Default.Album,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            // An odd final pair still lays out as half a row — weight(1f)
                            // on a lone cell would otherwise stretch it across the screen.
                            if (pair.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}
