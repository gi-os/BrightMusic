package com.lightphone.spotify.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.thelightphone.sdk.ui.LightThemeTokens

/**
 * An e-ink-style shutter reveal for a newly loaded track.
 *
 * Nine horizontal slats of the theme background collapse top-to-bottom with a slight
 * stagger, so the cover appears the way this panel's own refresh looks — bands wiping
 * clean — rather than a stock crossfade. Runs once per [key] change (track uri), costs a
 * single Canvas layer for ~550ms, and draws nothing at all once settled.
 *
 * Deliberately not a shader: AGSL needs API 33 and a separate render path, and slats of
 * `drawRect` read just as crisply at this size.
 */
@Composable
fun TrackLoadReveal(
    key: Any?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val progress = remember { Animatable(1f) }
    var lastKey by remember { mutableStateOf<Any?>(null) }

    LaunchedEffect(key) {
        if (key != null && key != lastKey) {
            lastKey = key
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                animationSpec = tween(durationMillis = 550, easing = LinearOutSlowInEasing),
            )
        }
    }

    val bg = LightThemeTokens.colors.background
    Box(modifier) {
        content()
        if (progress.value < 1f) {
            Canvas(Modifier.matchParentSize()) {
                val bands = 9
                val bandH = size.height / bands
                for (i in 0 until bands) {
                    // Each slat starts a beat after the one above it; all finish by 1.0.
                    val start = i.toFloat() / (bands * 2f)
                    val local = ((progress.value - start) / (1f - start)).coerceIn(0f, 1f)
                    val cover = bandH * (1f - local)
                    if (cover > 0.5f) {
                        drawRect(
                            color = bg,
                            topLeft = Offset(0f, i * bandH),
                            size = Size(size.width, cover),
                        )
                    }
                }
            }
        }
    }
}
