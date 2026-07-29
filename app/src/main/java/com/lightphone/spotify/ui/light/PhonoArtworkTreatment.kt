package com.lightphone.spotify.ui.light

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil.size.Size
import coil.transform.Transformation

/**
 * How album art is rendered on the Light Phone III panel.
 *
 * The panel is a full-colour AMOLED; LightOS just pins Android's daltonizer to
 * monochromacy. [COLOR] lifts that while a cover is on screen (see [ColorMode]), so real
 * cover art is possible. The greyscale modes exist for when you would rather leave the
 * phone mono — either because you did not grant `WRITE_SECURE_SETTINGS`, or because the
 * whole point of the device is that it does not light up in colour.
 *
 * When the phone does stay mono, only luminance survives, and the matte-ish panel costs
 * perceived contrast — so [GREY] and [DITHER] both apply a contrast curve rather than a
 * straight luminance pass, which reads muddy.
 */
enum class ArtworkTreatment {
    /** No artwork at all — upstream phono's text-only look. */
    OFF,

    /**
     * Real colour: no processing, plus [ColorMode] lifting the forced greyscale for as
     * long as a cover is visible. Falls back to looking like [GREY] (untouched art, mono
     * panel) if `WRITE_SECURE_SETTINGS` was never granted over adb.
     */
    COLOR,

    /** Contrast-boosted greyscale. Smooth, but wide gradients band on the panel. */
    GREY,

    /**
     * Contrast-boosted greyscale plus an 8×8 Bayer ordered dither.
     *
     * Trades real tonal levels for apparent detail: dithering four levels reads as
     * more texture than sixteen smooth ones do on a matte greyscale display, and it
     * hides the banding that [GREY] shows in skies and gradient sleeves.
     */
    DITHER,
    ;

    companion object {
        val DEFAULT = COLOR

        fun fromKey(key: String?): ArtworkTreatment =
            entries.firstOrNull { it.name == key } ?: DEFAULT
    }
}

/**
 * Process-wide observable artwork treatment, so changing it in Settings recomposes
 * every visible cover. Mirrors how [ThemePreferences] drives `LightThemeController`.
 */
object ArtworkSettings {
    var treatment: ArtworkTreatment by mutableStateOf(ArtworkTreatment.DEFAULT)
        private set

    /** Also gates the Now Playing cover specifically — art in lists, none on the player. */
    var showNowPlayingArt: Boolean by mutableStateOf(true)
        private set

    fun load(prefs: ArtworkPreferences) {
        treatment = prefs.treatment()
        showNowPlayingArt = prefs.showNowPlayingArt()
    }

    fun setTreatment(prefs: ArtworkPreferences, value: ArtworkTreatment) {
        treatment = value
        prefs.setTreatment(value)
    }

    fun setShowNowPlayingArt(prefs: ArtworkPreferences, value: Boolean) {
        showNowPlayingArt = value
        prefs.setShowNowPlayingArt(value)
    }
}

class ArtworkPreferences(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun treatment(): ArtworkTreatment = ArtworkTreatment.fromKey(prefs.getString(KEY_TREATMENT, null))

    fun setTreatment(value: ArtworkTreatment) {
        prefs.edit().putString(KEY_TREATMENT, value.name).apply()
    }

    fun showNowPlayingArt(): Boolean = prefs.getBoolean(KEY_NOW_PLAYING_ART, true)

    fun setShowNowPlayingArt(value: Boolean) {
        prefs.edit().putBoolean(KEY_NOW_PLAYING_ART, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "phono_artwork"
        private const val KEY_TREATMENT = "treatment"
        private const val KEY_NOW_PLAYING_ART = "now_playing_art"
    }
}

/**
 * Coil transformation that maps cover art onto what the LPIII can actually show.
 *
 * Runs off the main thread inside Coil's pipeline and the result is memory- and
 * disk-cached under [cacheKey], so a given cover is processed once per size.
 */
class LightPanelArtTransformation(
    private val dither: Boolean,
    /** 1.0 = untouched. Above 1 steepens the curve around mid grey. */
    private val contrast: Float = DEFAULT_CONTRAST,
    /** Output grey levels when dithering. 4 is the sweet spot on this panel. */
    private val levels: Int = DEFAULT_LEVELS,
) : Transformation {

    override val cacheKey: String = "${javaClass.name}-$dither-$contrast-$levels"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width <= 0 || height <= 0) return input

        // Coil documents the input config as ARGB_8888 *or* RGBA_F16, and getPixels /
        // setPixels throw outright on RGBA_F16. Wide-gamut covers do turn up, so convert
        // rather than crash — and if the copy fails, hand back the original untouched.
        val source = if (input.config == Bitmap.Config.RGBA_F16) {
            input.copy(Bitmap.Config.ARGB_8888, false) ?: return input
        } else {
            input
        }

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // Precompute the contrast curve for all 256 input greys — one table beats
        // doing the float maths per pixel on a 600×600 cover.
        val curve = IntArray(256) { grey ->
            val normalized = grey / 255f
            val boosted = ((normalized - 0.5f) * contrast + 0.5f).coerceIn(0f, 1f)
            (boosted * 255f + 0.5f).toInt()
        }

        val safeLevels = levels.coerceAtLeast(2)
        val step = 255f / (safeLevels - 1)

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val index = rowOffset + x
                val pixel = pixels[index]
                val alpha = pixel ushr 24 and 0xFF
                val red = pixel ushr 16 and 0xFF
                val green = pixel ushr 8 and 0xFF
                val blue = pixel and 0xFF

                // Rec. 709 luma — matches how the panel weights the channels it drops.
                val luma = (red * 2126 + green * 7152 + blue * 722) / 10000
                var grey = curve[luma.coerceIn(0, 255)]

                if (dither) {
                    // Ordered dither: nudge by the threshold at this position, then snap
                    // to the nearest level. Neighbouring pixels get different nudges, so
                    // a flat mid-tone becomes a stable pattern instead of a hard edge.
                    val threshold = BAYER_8X8[(y and 7) * 8 + (x and 7)]
                    val bias = (threshold / 64f - 0.5f) * step
                    val nudged = (grey + bias).coerceIn(0f, 255f)
                    grey = (Math.round(nudged / step) * step).toInt().coerceIn(0, 255)
                }

                pixels[index] = (alpha shl 24) or (grey shl 16) or (grey shl 8) or grey
            }
        }

        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    override fun equals(other: Any?): Boolean =
        other is LightPanelArtTransformation &&
            other.dither == dither &&
            other.contrast == contrast &&
            other.levels == levels

    override fun hashCode(): Int = cacheKey.hashCode()

    companion object {
        /**
         * Tuned against the matte panel, and consistent with the rest of this fork's
         * LPIII contrast handling: hues are gone, so the only legibility lever left is
         * a steeper tonal curve.
         */
        const val DEFAULT_CONTRAST = 1.35f
        const val DEFAULT_LEVELS = 4

        /**
         * Classic 8×8 Bayer matrix, values 0..63. Larger than the usual 4×4 because
         * album art has large flat regions where a 4×4 pattern is visible as a grid.
         */
        private val BAYER_8X8 = intArrayOf(
            0, 32, 8, 40, 2, 34, 10, 42,
            48, 16, 56, 24, 50, 18, 58, 26,
            12, 44, 4, 36, 14, 46, 6, 38,
            60, 28, 52, 20, 62, 30, 54, 22,
            3, 35, 11, 43, 1, 33, 9, 41,
            51, 19, 59, 27, 49, 17, 57, 25,
            15, 47, 7, 39, 13, 45, 5, 37,
            63, 31, 55, 23, 61, 29, 53, 21,
        )

        /** Null when the bitmap should be shown as-is, so callers pass it straight to Coil. */
        fun forTreatment(treatment: ArtworkTreatment): LightPanelArtTransformation? =
            when (treatment) {
                // COLOR deliberately does no processing: the panel shows the real thing.
                ArtworkTreatment.OFF, ArtworkTreatment.COLOR -> null
                ArtworkTreatment.GREY -> LightPanelArtTransformation(dither = false)
                ArtworkTreatment.DITHER -> LightPanelArtTransformation(dither = true)
            }
    }
}
