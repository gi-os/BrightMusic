package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * One item in a 2-across cover grid: square artwork (in colour — the artwork composable
 * holds the panel's colour mode while composed) with a title and subtitle beneath.
 *
 * Shared by Albums, Playlists and Podcasts so the three grids cannot drift apart.
 */
@Composable
fun PhonoGridCell(
    name: String,
    subtitle: String?,
    artUrl: String?,
    disabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    modifier: Modifier = Modifier,
) {
    val colors = LightThemeTokens.colors
    val textColor = if (disabled) PhonoSemanticColors.DisabledIcon else colors.content
    Column(
        modifier = modifier.tapWithLongPress(
            enabled = !disabled,
            onClick = onClick,
            onLongClick = onLongClick,
        ),
    ) {
        PhonoFallbackImage(
            imageUrl = artUrl,
            placeholderIcon = placeholderIcon,
            placeholderIconSize = legacyNToGridDp(40),
            disabled = disabled,
            crossfade = false,
            decodeSize = legacyNToGridDp(180),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        Spacer(Modifier.height(legacyNToGridDp(6)))
        LightText(
            text = name,
            variant = LightTextVariant.Copy,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                color = textColor.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
