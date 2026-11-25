package app.aura.ring.core

import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Temporal smoothing for effect frames and brightness.
 *
 * Effects are pure functions of time; this is the only stateful part of the
 * pipeline. It turns abrupt mode changes, notification pops and stepped
 * brightness into a soft cross-fade. Effects that must be crisp (strobe,
 * blink, sweep) pass `snap = true`.
 */
class FrameSmoother {
    private val current = IntArray(Effects.PIXELS)

    fun reset() {
        current.fill(0)
    }

    fun current(): IntArray = current

    fun smooth(target: IntArray, dtMs: Float, tauMs: Float, snap: Boolean): IntArray {
        val n = minOf(target.size, current.size)
        if (snap || tauMs <= 0.5f) {
            for (i in 0 until n) current[i] = normalise(target[i])
            return current
        }
        val dt = dtMs.coerceIn(1f, 250f)
        val k = (1f - exp(-dt / tauMs)).coerceIn(0f, 1f)
        for (i in 0 until n) {
            val from = current[i]
            val to = normalise(target[i])
            val fr = (from shr 16) and 0xFF
            val fg = (from shr 8) and 0xFF
            val fb = from and 0xFF
            val tr = (to shr 16) and 0xFF
            val tg = (to shr 8) and 0xFF
            val tb = to and 0xFF
            val r = (fr + (tr - fr) * k).roundToInt().coerceIn(0, 255)
            val g = (fg + (tg - fg) * k).roundToInt().coerceIn(0, 255)
            val b = (fb + (tb - fb) * k).roundToInt().coerceIn(0, 255)
            current[i] = if (r == 0 && g == 0 && b == 0) 0
            else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return current
    }

    private fun normalise(color: Int): Int {
        if (color == 0) return 0
        if ((color and 0xFFFFFF) == 0) return 0
        return color or 0xFF000000.toInt()
    }
}

/** Smooths a single 0..1 style value, used for hardware brightness. */
class ValueSmoother(initial: Float = 0f) {
    var value: Float = initial
        private set
    private var started = false

    fun reset(to: Float) {
        value = to
        started = false
    }

    fun smooth(target: Float, dtMs: Float, tauMs: Float, snap: Boolean = false): Float {
        if (snap || !started || tauMs <= 0.5f) {
            value = target
            started = true
            return value
        }
        val dt = dtMs.coerceIn(1f, 250f)
        val k = (1f - exp(-dt / tauMs)).coerceIn(0f, 1f)
        value += (target - value) * k
        return value
    }
}
