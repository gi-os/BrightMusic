package com.lightphone.spotify.ui.light

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The dominant colour of a cover, for the player's background wash.
 *
 * Deliberately not androidx.palette: this needs one number, the dependency is another library in
 * an APK that already carries librespot and ML Kit, and Palette's quantiser is tuned for picking
 * *swatches* (vibrant, muted, dark-vibrant) out of photographs. Sleeve art is usually two or
 * three flat colours, where a saturation-weighted bin count gives a better answer in far less code.
 *
 * The image is fetched at 32px through the app's own Coil loader, so it is nearly always already
 * in the memory cache — the full-size cover is on screen at the time. `allowHardware(false)` is
 * required: a hardware bitmap has no pixels to read and `getPixels` throws on it.
 *
 * Returns the theme's own neutral until the answer arrives, so the gradient fades in rather than
 * snapping from a wrong colour.
 */
@Composable
fun rememberArtworkAccent(url: String?, fallback: Color): Color {
    val context = LocalContext.current
    var accent by remember(url) { mutableStateOf(fallback) }
    LaunchedEffect(url) {
        if (url.isNullOrBlank()) {
            accent = fallback
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(ACCENT_SAMPLE_PX)
                    .allowHardware(false)
                    .build()
                (context.imageLoader.execute(request) as? SuccessResult)
                    ?.drawable
                    ?.let { it as? BitmapDrawable }
                    ?.bitmap
            }.getOrNull()
        } ?: return@LaunchedEffect
        accent = withContext(Dispatchers.Default) { dominantColor(bitmap, fallback) }
    }
    return accent
}

private const val ACCENT_SAMPLE_PX = 32

/**
 * Most-represented colour, with washed-out and near-black pixels weighted down.
 *
 * Colours are bucketed 5 bits per channel (32 levels) so shades of one ink count together —
 * without that, a gradient-printed sleeve has ten thousand unique colours and every one is
 * equally "dominant". Each pixel's vote is its saturation, so a cover that is 80% white card
 * still yields the ink colour rather than white.
 */
private fun dominantColor(bitmap: Bitmap, fallback: Color): Color {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return fallback
    val pixels = IntArray(w * h)
    runCatching { bitmap.getPixels(pixels, 0, w, 0, 0, w, h) }.onFailure { return fallback }

    val votes = HashMap<Int, Float>()
    var bestKey = -1
    var bestWeight = 0f
    for (pixel in pixels) {
        val a = (pixel ushr 24) and 0xFF
        if (a < 128) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        // Saturation as the vote, with a floor so a genuinely monochrome sleeve still returns
        // its grey rather than nothing at all.
        val saturation = if (max == 0) 0f else (max - min).toFloat() / max
        val brightness = max / 255f
        val weight = 0.15f + saturation * (0.35f + brightness * 0.5f)
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        val total = (votes[key] ?: 0f) + weight
        votes[key] = total
        if (total > bestWeight) {
            bestWeight = total
            bestKey = key
        }
    }
    if (bestKey < 0) return fallback
    // Bucket centre, not a corner: the low bits were thrown away, so add half a bucket back.
    val r = (((bestKey shr 10) and 0x1F) shl 3) or 0x04
    val g = (((bestKey shr 5) and 0x1F) shl 3) or 0x04
    val b = ((bestKey and 0x1F) shl 3) or 0x04
    return Color(r, g, b)
}
