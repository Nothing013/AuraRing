package app.aura.ring.core

import app.aura.ring.BatteryInfo
import app.aura.ring.NotifPattern
import app.aura.ring.Overlay
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import android.graphics.Color
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure frame renderer shared by the foreground service and the live UI
 * preview. Keeping it side-effect free means the preview always matches what
 * the hardware is doing.
 */
object Effects {

    fun render(
        config: RingConfig,
        nowMs: Long,
        battery: BatteryInfo,
        bands: FloatArray,
        overlay: Overlay?,
        perPixel: Boolean,
    ): IntArray {
        if (overlay != null) {
            val age = nowMs - overlay.startedAt
            if (age in 0..overlay.durationMs) {
                return overlayFrame(overlay, nowMs, perPixel)
            }
        }
        if (config.chargingArc && battery.charging) {
            return batteryFrame(battery, nowMs, perPixel)
        }
        return when (config.mode) {
            RingMode.OFF -> IntArray(8)
            RingMode.SOLID -> solid(config.color)
            RingMode.RAINBOW -> rainbow(config, nowMs, perPixel)
            RingMode.BREATHE -> breathe(config, nowMs)
            RingMode.COMET -> comet(config, nowMs, perPixel)
            RingMode.SPECTRUM -> spectrum(config, bands, perPixel)
            RingMode.SPLIT -> split(config, perPixel)
            RingMode.SPIN -> spin(config, nowMs, perPixel)
            RingMode.POLICE -> police(config, nowMs, perPixel)
            RingMode.FIRE -> fire(config, nowMs, perPixel)
            RingMode.AURORA -> aurora(config, nowMs, perPixel)
            RingMode.BATTERY -> batteryFrame(battery, nowMs, perPixel)
        }
    }

    private fun split(config: RingConfig, perPixel: Boolean): IntArray {
        if (!perPixel) return solid(lerpColor(config.color, config.color2, 0.5f))
        return IntArray(8) { i ->
            if (i < 4) config.color else config.color2
        }
    }

    private fun spin(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val speed = config.speed.coerceIn(0.15f, 4f)
        val step = ((now * speed / 110f).toInt()) % 8
        if (!perPixel) {
            val isA = (step % 2 == 0)
            return solid(if (isA) config.color else config.color2)
        }
        val spotA = step
        val spotB = (step + 4) % 8
        return IntArray(8) { i ->
            when (i) {
                spotA -> config.color
                spotB -> config.color2
                (spotA - 1 + 8) % 8 -> scale(config.color, 0.35f)
                (spotB - 1 + 8) % 8 -> scale(config.color2, 0.35f)
                else -> 0
            }
        }
    }

    private fun police(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val speed = config.speed.coerceIn(0.2f, 3f)
        val phase = ((now * speed / 80L) % 12L).toInt()
        val red = 0xFFFF1818.toInt()
        val blue = 0xFF1855FF.toInt()
        val colorA = when (phase) {
            0, 2 -> red
            else -> 0
        }
        val colorB = when (phase) {
            6, 8 -> blue
            else -> 0
        }
        if (!perPixel) {
            val c = if (phase < 6) colorA else colorB
            return solid(c)
        }
        return IntArray(8) { i ->
            if (i < 4) colorA else colorB
        }
    }

    private fun fire(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val firePalette = intArrayOf(
            0xFFFF1100.toInt(),
            0xFFFF4400.toInt(),
            0xFFFF8800.toInt(),
            0xFFFFCC00.toInt()
        )
        val speed = config.speed.coerceIn(0.2f, 3f)
        if (!perPixel) {
            val flame = (0.7f + 0.3f * sin(now * speed * 0.008).toFloat()).coerceIn(0f, 1f)
            return solid(scale(firePalette[1], flame))
        }
        return IntArray(8) { i ->
            val phase = (now * speed * 0.006f + i * 1.3f)
            val flick = (0.6f + 0.4f * sin(phase.toDouble()) * cos((phase * 1.7f).toDouble())).toFloat().coerceIn(0.2f, 1f)
            val colorIdx = ((sin(phase.toDouble() * 0.7) * 0.5 + 0.5) * (firePalette.size - 1)).toInt().coerceIn(0, firePalette.size - 1)
            scale(firePalette[colorIdx], flick)
        }
    }

    private fun aurora(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val speed = config.speed.coerceIn(0.1f, 4f)
        val baseHue = 130f
        if (!perPixel) {
            val h = (baseHue + sin(now * speed * 0.001) * 45f).toFloat()
            return solid(hsv(h, 0.95f, 1f))
        }
        return IntArray(8) { i ->
            val wave = sin((now * speed * 0.0018) + (i * PI * 2.0 / 8.0)).toFloat()
            val h = (baseHue + wave * 55f + 360f) % 360f
            val bright = (0.5 + 0.5 * cos((now * speed * 0.0015 + i).toDouble())).toFloat()
            hsv(h, 0.92f, 0.35f + 0.65f * bright)
        }
    }

    private fun rainbow(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val base = (now * 0.00012f * config.speed.coerceIn(0.1f, 4f) * 360f) % 360f
        if (!perPixel) return solid(hsv(base, 0.92f, 1f))
        return IntArray(8) { i ->
            hsv((base + i * 45f) % 360f, 0.92f, 1f)
        }
    }

    private fun breathe(config: RingConfig, now: Long): IntArray {
        val period = config.breatheMs.coerceIn(500, 10_000).toFloat()
        val phase = (now % period.toLong()) / period
        val env = 0.06f + 0.94f * (0.5f - 0.5f * cos(2.0 * PI * phase)).toFloat()
        return solid(scale(config.color, env))
    }

    private fun comet(config: RingConfig, now: Long, perPixel: Boolean): IntArray {
        val speed = config.speed.coerceIn(0.15f, 4f)
        val head = ((now * speed / 140f).toInt()) % 8
        if (!perPixel) {
            val env = 0.35f + 0.65f * (0.5f + 0.5f * cos(2.0 * PI * now / 700.0)).toFloat()
            return solid(scale(config.color, env))
        }
        return IntArray(8) { i ->
            val distance = (head - i + 8) % 8
            val intensity = when (distance) {
                0 -> 1f
                1 -> 0.55f
                2 -> 0.25f
                3 -> 0.08f
                else -> 0f
            }
            scale(config.color, intensity)
        }
    }

    private fun spectrum(config: RingConfig, bands: FloatArray, perPixel: Boolean): IntArray {
        if (!perPixel) {
            val avg = if (bands.isEmpty()) 0f else bands.average().toFloat()
            return solid(scale(config.color, 0.04f + 0.96f * avg.coerceIn(0f, 1f)))
        }
        return IntArray(8) { i ->
            val value = bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val base = lerpColor(config.color, config.color2, i / 7f)
            scale(base, 0.04f + 0.96f * value)
        }
    }

    private fun batteryFrame(battery: BatteryInfo, now: Long, perPixel: Boolean): IntArray {
        val level = battery.level.coerceIn(0, 100)
        val color = when {
            level <= 15 -> 0xFFF87171.toInt()
            level <= 40 -> 0xFFFBBF24.toInt()
            else -> 0xFF4ADE80.toInt()
        }
        if (!perPixel) {
            val value = if (battery.charging) {
                0.5f + 0.5f * sinPulse(now, 900)
            } else {
                0.22f + 0.78f * (level / 100f)
            }
            return solid(scale(color, value))
        }
        val filled = ((level / 100f) * 8f).toInt().coerceIn(0, 8)
        return IntArray(8) { i ->
            when {
                i < filled -> color
                i == filled && battery.charging -> scale(color, 0.25f + 0.75f * sinPulse(now, 700))
                i == filled && level > 0 -> color
                else -> scale(color, 0.05f)
            }
        }
    }

    private fun overlayFrame(overlay: Overlay, now: Long, perPixel: Boolean): IntArray {
        val age = (now - overlay.startedAt).coerceAtLeast(0)
        return when (overlay.pattern) {
            NotifPattern.PULSE -> {
                val phase = (age % 480L) / 480f
                val env = (sin(PI * phase).pow(2)).toFloat()
                solid(scale(overlay.color, 0.05f + 0.95f * env))
            }

            NotifPattern.BLINK ->
                if ((age / 240L) % 2L == 0L) solid(overlay.color) else IntArray(8)

            NotifPattern.BREATHE -> {
                val env = (0.5f - 0.5f * cos(2.0 * PI * age / 1800.0)).toFloat()
                solid(scale(overlay.color, 0.06f + 0.94f * env))
            }

            NotifPattern.SWEEP -> {
                if (!perPixel) {
                    solid(scale(overlay.color, 0.4f + 0.6f * sinPulse(now, 600)))
                } else {
                    val head = ((age / 110L) % 8L).toInt()
                    IntArray(8) { i ->
                        val distance = (head - i + 8) % 8
                        val intensity = when (distance) {
                            0 -> 1f
                            1 -> 0.5f
                            2 -> 0.2f
                            else -> 0f
                        }
                        scale(overlay.color, intensity)
                    }
                }
            }
        }
    }

    private fun solid(color: Int): IntArray = IntArray(8) { color }

    private fun sinPulse(now: Long, periodMs: Int): Float =
        (0.5f + 0.5f * sin(2.0 * PI * (now % periodMs) / periodMs)).toFloat()

    private fun scale(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        if (f <= 0.001f) return 0
        val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int =
        Color.HSVToColor(floatArrayOf(hue % 360f, saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f)))

    private fun lerpColor(a: Int, b: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val ca = (a shr shift) and 0xFF
            val cb = (b shr shift) and 0xFF
            return (ca + (cb - ca) * f).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
