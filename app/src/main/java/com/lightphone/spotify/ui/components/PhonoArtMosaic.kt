package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.lightphone.spotify.ui.light.legacyNToGridDp

/**
 * Four covers in a square, the way Spotify draws a playlist that has no cover of its own.
 *
 * Used for the "Made for you" folder, which is not a playlist and so has no artwork anywhere to
 * fetch: what it looks like has to be built out of what it holds. Borrowing the mosaic is the point
 * — it already means "several things in one" everywhere else on the phone.
 *
 * With fewer than four covers it draws the first one alone rather than a grid with holes in it. A
 * quarter-empty square reads as artwork that failed to load, which is a bug report waiting to
 * happen.
 */
@Composable
fun PhonoArtMosaic(
    artUrls: List<String?>,
    modifier: Modifier = Modifier,
    disabled: Boolean = false,
    placeholderIcon: ImageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
    /** The size of the whole square. Each tile is decoded at half of it. */
    size: Dp = legacyNToGridDp(180),
) {
    val tiles = artUrls.filterNotNull().filter { it.isNotBlank() }.take(4)
    if (tiles.size < 4) {
        PhonoFallbackImage(
            imageUrl = tiles.firstOrNull(),
            placeholderIcon = placeholderIcon,
            placeholderIconSize = size / 4,
            disabled = disabled,
            crossfade = false,
            decodeSize = size,
            modifier = modifier,
        )
        return
    }
    Column(modifier) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                for (column in 0 until 2) {
                    PhonoFallbackImage(
                        imageUrl = tiles[row * 2 + column],
                        placeholderIcon = placeholderIcon,
                        placeholderIconSize = size / 8,
                        disabled = disabled,
                        crossfade = false,
                        decodeSize = size / 2,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}
