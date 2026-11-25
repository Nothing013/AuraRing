package app.aura.ring.core

import android.graphics.Color

/**
 * Colour ramps used by the flowing modes. CUSTOM uses the two colours picked in
 * the app, so the ring always answers to the user's palette first.
 */
object Palettes {
    const val CUSTOM = 0
    const val SPECTRUM = 1
    const val CYBERPUNK = 2
    const val SUNSET = 3
    const val OCEAN = 4
    const val PASTEL = 5
    const val FIRE = 6

    data class Pal(val id: Int, val label: String, val colors: IntArray?)

    val ALL = listOf(
        Pal(CUSTOM, "Custom", null),
        Pal(SPECTRUM, "Spectrum", null),
        Pal(CYBERPUNK, "Cyberpunk", intArrayOf(
            0xFFFF007F.toInt(), 0xFF7928CA.toInt(), 0xFF0070F3.toInt(), 0xFF00DFD8.toInt(),
        )),
        Pal(SUNSET, "Sunset", intArrayOf(
            0xFFFF1744.toInt(), 0xFFFF6D00.toInt(), 0xFFFFD600.toInt(), 0xFFE040FB.toInt(),
        )),
        Pal(OCEAN, "Ocean", intArrayOf(
            0xFF00E5FF.toInt(), 0xFF1DE9B6.toInt(), 0xFF00E676.toInt(), 0xFF2979FF.toInt(),
        )),
        Pal(PASTEL, "Pastel", intArrayOf(
            0xFFFF4081.toInt(), 0xFF7C4DFF.toInt(), 0xFF00E5FF.toInt(), 0xFF00E676.toInt(),
            0xFFFF6E40.toInt(),
        )),
        Pal(FIRE, "Fire", intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF3D00.toInt(), 0xFFFF9100.toInt(), 0xFFFFEA00.toInt(),
        )),
    )

    fun label(id: Int): String = ALL.firstOrNull { it.id == id }?.label ?: "Custom"

    /**
     * Samples the palette at [t] (0..1, wrapping). [a] and [b] are the user's
     * colours, used by CUSTOM.
     */
    fun sample(id: Int, t: Float, a: Int, b: Int): Int {
        val pos = ((t % 1f) + 1f) % 1f
        return when (id) {
            CUSTOM -> lerpColor(a, b, triangle(pos))
            SPECTRUM -> Color.HSVToColor(floatArrayOf(pos * 360f, 0.92f, 1f))
            else -> {
                val colors = ALL.firstOrNull { it.id == id }?.colors ?: return lerpColor(a, b, pos)
                val scaled = pos * colors.size
                val i = scaled.toInt() % colors.size
                val j = (i + 1) % colors.size
                lerpColor(colors[i], colors[j], scaled - scaled.toInt())
            }
        }
    }

    /** 0 -> 0, 0.5 -> 1, 1 -> 0. */
    private fun triangle(x: Float): Float = if (x < 0.5f) x * 2f else (1f - x) * 2f

    fun lerpColor(a: Int, b: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val ha = Color.red(a) / 255f
        val hb = Color.red(b) / 255f
        val ga = Color.green(a) / 255f
        val gb = Color.green(b) / 255f
        val ba = Color.blue(a) / 255f
        val bb = Color.blue(b) / 255f

        val achromatic = isAchromatic(a) || isAchromatic(b)
        val v = hsv(a)
        val w = hsv(b)
        val hue = if (achromatic) {
            if (v[1] >= w[1]) v[0] else w[0]
        } else {
            var diff = w[0] - v[0]
            if (diff > 180f) diff -= 360f else if (diff < -180f) diff += 360f
            val h = (v[0] + diff * f) % 360f
            if (h < 0f) h + 360f else h
        }
        val sat = (v[1] + (w[1] - v[1]) * f).coerceIn(0f, 1f)
        val value = (v[2] + (w[2] - v[2]) * f).coerceIn(0f, 1f)

        // Fall back to a plain channel ramp when the colours are near-grey,
        // which avoids HSV hue noise on white / black.
        if (achromatic && value < 0.02f) return 0
        if (achromatic) {
            return Color.rgb(
                ((ha + (hb - ha) * f) * 255f).toInt().coerceIn(0, 255),
                ((ga + (gb - ga) * f) * 255f).toInt().coerceIn(0, 255),
                ((ba + (bb - ba) * f) * 255f).toInt().coerceIn(0, 255),
            )
        }
        return Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    private fun isAchromatic(color: Int): Boolean {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max == 0 || (max - min) < max * 0.08f
    }

    private val hsvScratch = FloatArray(3)

    private fun hsv(color: Int): FloatArray {
        val out = FloatArray(3)
        Color.colorToHSV(color, out)
        return out
    }
}
