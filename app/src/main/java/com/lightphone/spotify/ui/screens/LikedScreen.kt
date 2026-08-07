package com.lightphone.spotify.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.lightphone.spotify.data.LikedFilter
import com.lightphone.spotify.ui.AppViewModel
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.lightClickable

/**
 * Liked songs and saved albums in one tab, switched by a chip pair in the title bar.
 *
 * These were two tabs. They are the same idea — things you saved — and the bar only fits six, so
 * they share one the way Playlists shares one between "By You" and "All". The chips are deliberately
 * the same control as Playlists': two lists that behave alike should not be switched two different
 * ways.
 *
 * Each half keeps its own screen and its own shell rather than being merged into one list. They are
 * not the same list: songs scrub by date and play a track, albums open a detail screen, and the
 * scroll indices, date indexes and paging states are per-list. Sharing only the chips keeps that
 * intact and means neither loses its place when you switch back.
 */
@Composable
fun LikedScreen(
    vm: AppViewModel,
    onOpenPlaying: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onOpenAlbum: (String, String) -> Unit,
    onOpenGlobalSearch: () -> Unit = {},
    onOpenOptions: () -> Unit = {},
) {
    val filter by vm.likedFilter.collectAsState()

    val chips: @Composable () -> Unit = {
        LikedFilterChips(selected = filter, onSelect = vm::setLikedFilter)
    }

    when (filter) {
        LikedFilter.Songs -> LikedSongsScreen(
            vm = vm,
            onOpenPlaying = onOpenPlaying,
            onPlayTrack = onPlayTrack,
            titleContent = chips,
            onOpenGlobalSearch = onOpenGlobalSearch,
            onOpenOptions = onOpenOptions,
        )
        LikedFilter.Albums -> AlbumsScreen(
            vm = vm,
            onOpenPlaying = onOpenPlaying,
            onOpenAlbum = onOpenAlbum,
            titleContent = chips,
            onOpenGlobalSearch = onOpenGlobalSearch,
            onOpenOptions = onOpenOptions,
        )
    }
}

@Composable
private fun LikedFilterChips(
    selected: LikedFilter,
    onSelect: (LikedFilter) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(legacyNToGridDp(8))) {
        LikedFilter.entries.forEach { filter ->
            LikedFilterChip(
                filter = filter,
                active = filter == selected,
                onSelect = onSelect,
            )
        }
    }
}

/** Same shape and colours as the Playlists chips, on purpose. */
@Composable
private fun LikedFilterChip(
    filter: LikedFilter,
    active: Boolean,
    onSelect: (LikedFilter) -> Unit,
) {
    val colors = LightThemeTokens.colors
    Box(
        modifier = Modifier
            .background(if (active) colors.content else PhonoSemanticColors.PlaceholderBg)
            .lightClickable { onSelect(filter) }
            .padding(
                horizontal = legacyNToGridDp(12),
                vertical = legacyNToGridDp(6),
            ),
    ) {
        LightText(
            text = filter.label,
            variant = LightTextVariant.Copy,
            color = if (active) colors.background else colors.content,
            maxLines = 1,
        )
    }
}
