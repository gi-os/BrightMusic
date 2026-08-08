package com.lightphone.spotify.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ColorArtworkEffect
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.LightPanelArtTransformation
import com.lightphone.spotify.ui.light.PhonoSemanticColors
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens

/** Square artwork with grey placeholder when there's no URL or the load fails. */
@Composable
fun PhonoFallbackImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    placeholderIconSize: Dp = legacyNToGridDp(100),
    placeholderText: String? = null,
    disabled: Boolean = false,
    contentDescription: String? = null,
    crossfade: Boolean = true,
    decodeSize: Dp? = null,
    /**
     * Crop by default, because every list thumbnail and square cover wants the frame filled.
     * The full-bleed player passes [ContentScale.Fit]: there the art *is* the screen, and
     * cropping a sleeve is destroying the thing the user opened the screen to look at.
     */
    contentScale: ContentScale = ContentScale.Crop,
) {
    var failed by remember(imageUrl) { mutableStateOf(false) }
    val iconTint = if (disabled) PhonoSemanticColors.DisabledIcon else LightThemeTokens.colors.content
    val treatment = ArtworkSettings.treatment

    if (imageUrl.isNullOrBlank() || failed || treatment == ArtworkTreatment.OFF) {
        Box(
            modifier = modifier.background(PhonoSemanticColors.PlaceholderBg),
            contentAlignment = Alignment.Center,
        ) {
            if (placeholderText != null) {
                LightText(
                    text = placeholderText,
                    variant = LightTextVariant.Copy,
                    color = iconTint,
                    align = TextAlign.Center,
                )
            } else {
                Icon(
                    placeholderIcon,
                    contentDescription = contentDescription,
                    tint = iconTint,
                    modifier = Modifier.size(placeholderIconSize),
                )
            }
        }
    } else {
        // Every rendered cover holds the panel in colour, not just the big ones. Colour used
        // to be held only by the player art and detail headers, so scrolling a playlist past
        // its header snapped the whole screen back to B&W while the row thumbnails were
        // still visible. The hold is refcounted (see [ColorMode]) and its release is
        // debounced, so a lazy list recycling rows does not strobe the panel.
        ColorArtworkEffect()
        val context = LocalContext.current
        val density = LocalDensity.current
        val request = remember(imageUrl, crossfade, decodeSize, density, treatment) {
            val builder = ImageRequest.Builder(context)
                .data(imageUrl)
                .crossfade(crossfade)
            LightPanelArtTransformation.forTreatment(treatment)?.let {
                builder.transformations(it)
            }
            if (decodeSize != null) {
                builder.size(with(density) { decodeSize.roundToPx() })
            }
            builder.build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            modifier = modifier.background(PhonoSemanticColors.PlaceholderBg),
            contentScale = contentScale,
            onState = { state ->
                if (state is AsyncImagePainter.State.Error) failed = true
            },
        )
    }
}

@Composable
fun PhonoDetailCover(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
) {
    PhonoFallbackImage(
        imageUrl = imageUrl,
        modifier = modifier.fillMaxSize(),
        placeholderIcon = placeholderIcon,
        placeholderIconSize = legacyNToGridDp(100),
    )
}
