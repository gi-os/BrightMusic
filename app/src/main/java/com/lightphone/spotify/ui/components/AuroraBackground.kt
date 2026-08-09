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
 * It is drawn across the **whole** screen, behind the top bar and the controls alike. Confining
 * it to part of the height put a visible line where it ended, and fading the sides put two dark
 * bands down them — both times the containment read worse than what it was containing. The only
 * edge treatment left is the fade downward.
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
        // Large enough that each blob's gradient is nearly transparent well before it reaches a
        // screen edge — which is what makes the removed side fade unnecessary rather than
        // missed. Three of them overlapping still read as one wash.
        val radius = w * 0.58f

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

        // Far enough apart to read as three lights rather than one glow.
        //
        // There is deliberately no side fade any more: it was added to hide discs clipped by the
        // canvas edge, and it read as two black gradients down the sides — worse than the thing
        // it was hiding. The radius below is what keeps the edges soft instead, and a blob whose
        // gradient has already fallen to nothing by the time it reaches the edge has no edge to
        // clip.
        //
        // Centres live in the top third: this canvas is the whole screen, and the aurora is
        // meant to sit above the track rather than behind it.
        blob(a, t1, cx = 0.27f, cy = 0.18f, spreadX = 0.09f, spreadY = 0.09f)
        blob(b, t2, cx = 0.74f, cy = 0.14f, spreadX = 0.09f, spreadY = 0.11f)
        blob(c, t3, cx = 0.50f, cy = 0.07f, spreadX = 0.14f, spreadY = 0.07f)

        // Fade the whole thing out downward into the theme background, so it belongs to the top
        // of the screen and the text below sits on a clean surface.
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to Color.Transparent,
                0.40f to background.copy(alpha = 0.45f),
                0.72f to background,
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
private const val BLOB_ALPHA = 0.34f

private const val PALETTE_FADE_MS = 2_000
private const val TWO_PI = (2 * Math.PI).toFloat()
