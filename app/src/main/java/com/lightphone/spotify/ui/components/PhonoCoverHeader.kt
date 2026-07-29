package com.lightphone.spotify.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyListScope
import com.lightphone.spotify.ui.light.ArtworkSettings
import com.lightphone.spotify.ui.light.ArtworkTreatment
import com.lightphone.spotify.ui.light.legacyNToGridDp
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant

/**
 * Cover art header for album / playlist / artist detail screens.
 *
 * Deliberately a scrolling list item rather than a pinned header: the LPIII is only
 * ~472dp tall, so a header that stayed put would leave room for three or four tracks.
 * This way the art is there when you arrive and gone as soon as you start scrolling.
 */
@Composable
fun PhonoCoverHeader(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    placeholderIcon: ImageVector = Icons.Default.MusicNote,
    /** Artist images are portraits and read better round, matching LightOS contact art. */
    circular: Boolean = false,
    widthFraction: Float = DEFAULT_WIDTH_FRACTION,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PhonoFallbackImage(
            imageUrl = imageUrl,
            placeholderIcon = placeholderIcon,
            placeholderIconSize = legacyNToGridDp(60),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(widthFraction)
                .aspectRatio(1f)
                .then(if (circular) Modifier.clip(CircleShape) else Modifier),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(legacyNToGridDp(10)))
            LightText(
                text = subtitle,
                variant = LightTextVariant.Detail,
                align = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(legacyNToGridDp(20)))
    }
}

/**
 * Adds [PhonoCoverHeader] as the first row of a list, or nothing at all when the user
 * has turned artwork off. Keeps the `if` out of every detail screen.
 */
fun LazyListScope.phonoCoverHeaderItem(
    imageUrl: String?,
    subtitle: String? = null,
    placeholderIcon: ImageVector? = null,
    circular: Boolean = false,
) {
    item(key = "phono-cover-header") {
        if (ArtworkSettings.treatment != ArtworkTreatment.OFF) {
            PhonoCoverHeader(
                imageUrl = imageUrl,
                subtitle = subtitle,
                placeholderIcon = placeholderIcon
                    ?: if (circular) Icons.Default.Person else Icons.Default.MusicNote,
                circular = circular,
            )
        }
    }
}

/** ~226dp on a 411dp-wide LPIII — big enough to read, small enough to leave tracks visible. */
private const val DEFAULT_WIDTH_FRACTION = 0.55f
