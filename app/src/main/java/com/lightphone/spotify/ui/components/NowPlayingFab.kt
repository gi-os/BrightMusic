package com.lightphone.spotify.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.lightClickable

/**
 * The current cover, bottom-centre, as the way back into the player.
 *
 * Deliberately the artwork and nothing else — no bar, no background, no title. A second bar
 * above the tab bar would eat a row of every list on the phone for information the player
 * already shows; the cover on its own is recognisable at a glance and costs the screen nothing
 * but the square it occupies.
 *
 * Sized and placed to match the now-playing slot in [PhonoTabBar], so on a tab screen the two
 * read as the same control in the same place rather than two ways back.
 *
 * Appears only when something is loaded, and fades rather than popping — it arrives when the
 * first track starts, which is a moment the user is already looking at the screen for.
 */
@Composable
fun BoxScope.NowPlayingFab(
    artUrl: String?,
    visible: Boolean,
    onClick: () -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp = legacyNToGridDp(12),
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.85f),
        exit = fadeOut(tween(160)) + scaleOut(tween(160), targetScale = 0.85f),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomPadding),
    ) {
        PhonoFallbackImage(
            imageUrl = artUrl,
            placeholderIcon = Icons.Default.MusicNote,
            placeholderIconSize = legacyNToGridDp(18),
            crossfade = false,
            decodeSize = legacyNToGridDp(44),
            contentDescription = "Now playing",
            modifier = Modifier
                .size(legacyNToGridDp(44))
                .lightClickable(onClick = onClick),
        )
    }
}
