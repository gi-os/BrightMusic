package com.lightphone.spotify.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlin.math.cos
import kotlin.math.sin

/**
 * A slow wash of the cover's own colours across the top of the player, fading out downward.
 *
 * Three soft radial blobs, each drifting on its own slow ellipse at a period that shares no
 * factor with the others (17s / 23s / 31s) — so the pattern never visibly repeats, which is what
 * separates an aurora from a loop. They are additive-ish by overlap alone: each is a radial
 * gradient from its colour at low alpha out to transparent, so where two cross, the colours mix.
 *
 * The colours come from [com.lightphone.spotify.ui.light.rememberArtworkPalette] and are
 * animated, so a new record slides its palette in over a couple of seconds rather than cutting.
 *
 * The fade to the background colour is drawn *over* the blobs rather than masked out of them:
 * an alpha mask needs an offscreen compositing layer, and this panel does not need the cost when
 * a plain vertical gradient in the theme's own background colour looks identical over it.
 *
 * Draws nothing at all when the palette is empty — no cover, or artwork turned off — so the
 * caller never has to decide whether it is worth composing.
 */
@Composable
fun AuroraBackground(
    colors: List<Color>,
    background: Color,
    modifier: Modifier = Modifier,
) {
    if (colors.isEmpty()) return

    // Animated per slot, so a track change slides the palette rather than cutting to it.
    val a by animateColorAsState(colors[0], tween(PALETTE_FADE_MS), label = "aurora-a")
    val b by animateColorAsState(colors.getOrElse(1) { colors[0] }, tween(PALETTE_FADE_MS), label = "aurora-b")
    val c by animateColorAsState(colors.getOrElse(2) { colors[0] }, tween(PALETTE_FADE_MS), label = "aurora-c")

    val drift = rememberInfiniteTransition(label = "aurora-drift")
    val t1 by drift.animateFloat(0f, 1f, cycle(17_000), label = "aurora-t1")
    val t2 by drift.animateFloat(0f, 1f, cycle(23_000), label = "aurora-t2")
    val t3 by drift.animateFloat(0f, 1f, cycle(31_000), label = "aurora-t3")

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val radius = w * 0.85f

        fun blob(color: Color, phase: Float, cx: Float, cy: Float, spreadX: Float, spreadY: Float) {
            val angle = phase * TWO_PI
            val centre = Offset(
                x = w * cx + cos(angle) * w * spreadX,
                y = h * cy + sin(angle) * h * spreadY,
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = BLOB_ALPHA), Color.Transparent),
                    center = centre,
                    radius = radius,
                ),
                radius = radius,
                center = centre,
            )
        }

        blob(a, t1, cx = 0.30f, cy = 0.20f, spreadX = 0.22f, spreadY = 0.18f)
        blob(b, t2, cx = 0.72f, cy = 0.32f, spreadX = 0.20f, spreadY = 0.22f)
        blob(c, t3, cx = 0.50f, cy = 0.10f, spreadX = 0.26f, spreadY = 0.14f)

        // Fade the whole thing out downward into the theme background, so it belongs to the top
        // of the screen and the text below sits on a clean surface.
        drawRect(
            brush = Brush.verticalGradient(
                0.0f to Color.Transparent,
                0.55f to background.copy(alpha = 0.55f),
                1.0f to background,
            ),
        )
    }
}

private fun cycle(millis: Int): InfiniteRepeatableSpec<Float> = infiniteRepeatable(
    animation = tween(millis, easing = LinearEasing),
    // Restart, not Reverse: the phase feeds a sine, so it already comes back on its own — and a
    // reversing phase makes each blob retrace its own path, which reads as a loop.
    repeatMode = RepeatMode.Restart,
)

/** Low enough that three overlapping blobs still sit behind text rather than competing with it. */
private const val BLOB_ALPHA = 0.30f

private const val PALETTE_FADE_MS = 2_000
private const val TWO_PI = (2 * Math.PI).toFloat()
