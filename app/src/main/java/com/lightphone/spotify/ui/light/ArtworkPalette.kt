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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A handful of colours out of a cover, for the player's aurora.
 *
 * Deliberately not androidx.palette: this needs three numbers, the dependency is another library
 * in an APK that already carries librespot and ML Kit, and Palette's quantiser is tuned for
 * pulling swatches out of photographs. Sleeve art is usually two or three flat inks, where a
 * saturation-weighted bin count gives a better answer in far less code.
 *
 * The cover is fetched at 32px through the app's own Coil loader, so it is nearly always already
 * in the memory cache — the full-size cover is on screen at the time. `allowHardware(false)` is
 * required: a hardware bitmap has no pixels to read and `getPixels` throws on it.
 */
@Composable
fun rememberArtworkPalette(url: String?, count: Int = 3): List<Color> {
    val context = LocalContext.current
    var palette by remember(url) { mutableStateOf(emptyList<Color>()) }
    LaunchedEffect(url, count) {
        if (url.isNullOrBlank()) {
            palette = emptyList()
            return@LaunchedEffect
        }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .size(SAMPLE_PX)
                    .allowHardware(false)
                    .build()
                (context.imageLoader.execute(request) as? SuccessResult)
                    ?.drawable
                    ?.let { it as? BitmapDrawable }
                    ?.bitmap
            }.getOrNull()
        } ?: return@LaunchedEffect
        palette = withContext(Dispatchers.Default) { dominantColors(bitmap, count) }
    }
    return palette
}

private const val SAMPLE_PX = 32

/**
 * The most-represented colours, washed-out and near-black pixels weighted down.
 *
 * Colours are bucketed 5 bits per channel (32 levels) so shades of one ink count together —
 * without that, a gradient-printed sleeve has ten thousand unique colours and every one is
 * equally "dominant". Each pixel votes with its saturation, so a cover that is 80% white card
 * still yields the ink colour rather than white.
 *
 * Picked colours must also differ from each other: taking the top three bins outright returns
 * three shades of the same blue on most sleeves, and an aurora built from those has nothing to
 * shift between. [MIN_SEPARATION] is in bucket space, so it is a like-for-like comparison.
 */
private fun dominantColors(bitmap: Bitmap, count: Int): List<Color> {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return emptyList()
    val pixels = IntArray(w * h)
    runCatching { bitmap.getPixels(pixels, 0, w, 0, 0, w, h) }.onFailure { return emptyList() }

    val votes = HashMap<Int, Float>()
    for (pixel in pixels) {
        if (((pixel ushr 24) and 0xFF) < 128) continue
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val saturation = if (max == 0) 0f else (max - min).toFloat() / max
        val brightness = max / 255f
        // A floor, so a genuinely monochrome sleeve still returns its grey rather than nothing.
        val weight = 0.15f + saturation * (0.35f + brightness * 0.5f)
        val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
        votes[key] = (votes[key] ?: 0f) + weight
    }
    if (votes.isEmpty()) return emptyList()

    val picked = mutableListOf<Int>()
    for ((key, _) in votes.entries.sortedByDescending { it.value }) {
        if (picked.size >= count) break
        if (picked.none { separation(it, key) < MIN_SEPARATION }) picked += key
    }
    val found = picked.map { key ->
        // Bucket centre, not a corner: the low bits were thrown away, so add half a bucket back.
        Color(
            (((key shr 10) and 0x1F) shl 3) or 0x04,
            (((key shr 5) and 0x1F) shl 3) or 0x04,
            ((key and 0x1F) shl 3) or 0x04,
        )
    }
    return padToCount(found, count)
}

/**
 * Make up the numbers when a sleeve genuinely has fewer colours than asked for.
 *
 * Plenty of covers are one ink on one ground, and [MIN_SEPARATION] rightly refuses to call two
 * shades of it separate colours. But the aurora needs three to have anything to shift between —
 * with one colour repeated it reads as a single static glow, which is what "I only see one
 * gradient" looks like.
 *
 * The extras are hue rotations of what was found, not arbitrary colours: they stay recognisably
 * of the same record, and rotating hue keeps the saturation and lightness that made the original
 * read well on this panel.
 */
private fun padToCount(found: List<Color>, count: Int): List<Color> {
    if (found.isEmpty() || found.size >= count) return found
    val out = found.toMutableList()
    val rotations = listOf(28f, -28f, 52f, -52f)
    var i = 0
    while (out.size < count && i < rotations.size) {
        out += out[i % found.size].rotateHue(rotations[i])
        i++
    }
    return out
}

private fun Color.rotateHue(degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this.toArgb(), hsv)
    hsv[0] = ((hsv[0] + degrees) % 360f + 360f) % 360f
    // A flat ground can be almost unsaturated; a rotation of nothing is still nothing, so give
    // the derived colours a floor to work with.
    hsv[1] = hsv[1].coerceAtLeast(0.35f)
    hsv[2] = hsv[2].coerceIn(0.35f, 0.95f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun separation(a: Int, b: Int): Int {
    val dr = (((a shr 10) and 0x1F) - ((b shr 10) and 0x1F))
    val dg = (((a shr 5) and 0x1F) - ((b shr 5) and 0x1F))
    val db = ((a and 0x1F) - (b and 0x1F))
    return kotlin.math.abs(dr) + kotlin.math.abs(dg) + kotlin.math.abs(db)
}

/** In 32-level bucket space: below this, two picks read as the same colour. */
private const val MIN_SEPARATION = 8
