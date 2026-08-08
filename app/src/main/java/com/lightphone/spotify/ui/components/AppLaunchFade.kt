package com.lightphone.spotify.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer

/**
 * Fades the app in on launch.
 *
 * The splash screen hands over to a fully drawn first frame, which on this panel reads as a
 * hard cut. One 450ms alpha ramp turns that into an arrival. It is process-scoped, not
 * composition-scoped ([faded] is a file-level `var`), so a config change or a return from
 * the background does not replay it — an animation you see more than once a launch stops
 * being a nice touch and becomes latency.
 *
 * Deliberately alpha rather than slats or a wipe: this screen is greyscale by default and a
 * geometric reveal on it looks like a rendering fault rather than an intro.
 */
@Composable
fun AppLaunchFade(content: @Composable BoxScope.() -> Unit) {
    var visible by remember { mutableStateOf(faded) }
    LaunchedEffect(Unit) {
        if (!faded) {
            visible = true
            faded = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 600, easing = LinearOutSlowInEasing),
        label = "app-launch-fade",
    )
    Box(
        Modifier
            .fillMaxSize()
            .alpha(progress)
            // A hair of scale under the fade. Alpha alone on a greyscale panel is easy to miss
            // — 4% of growth is what makes it read as the app arriving rather than a flicker.
            .graphicsLayer {
                val s = 0.96f + 0.04f * progress
                scaleX = s
                scaleY = s
            },
        content = content,
    )
}

/** Whether this process has already played the launch fade. */
private var faded = false
