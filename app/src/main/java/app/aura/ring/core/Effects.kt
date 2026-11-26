package app.aura.ring.core

import android.graphics.Color
import app.aura.ring.BatteryInfo
import app.aura.ring.NotifPattern
import app.aura.ring.Overlay
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import app.aura.ring.audio.AudioState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure frame renderer. The service and the in-app preview both call this, so
 * what you see in the app is exactly what the ring receives.
 *
 * Every frame is 8 pixels. Devices without per-pixel control (Shizuku) collapse
 * the frame with [collapse] instead of rendering a different effect, so the
 * preview and the hardware never disagree about what the effect is doing.
 */
object Effects {
    const val PIXELS = 8
    private val BLACK = IntArray(PIXELS)

    fun render(
        config: RingConfig,
        nowMs: Long,
        battery: BatteryInfo,
        audio: AudioState,
        overlay: Overlay?,
    ): IntArray {
        val ov = overlay
        if (ov != null) {
            // Events are allowed even in Off mode: the mode controls the
            // ambient effect, not whether notifications can light the ring.
            val age = nowMs - ov.startedAt
            if (age in 0..ov.durationMs) {
                return if (ov.color == 0) {
                    effectFrame(config, nowMs, battery, audio, age)
                } else {
                    overlayFrame(ov, nowMs)
                }
            }
        }
        return baseFrame(config, nowMs, battery, audio)
    }

    private fun baseFrame(
        config: RingConfig,
        now: Long,
        battery: BatteryInfo,
        audio: AudioState,
    ): IntArray {
        val music = config.music && audio.hasSignal
        if (config.chargingArc && battery.charging && battery.level >= 0 &&
            config.mode != RingMode.OFF && config.mode != RingMode.NOTIFICATIONS_ONLY && !music
        ) {
            return batteryFrame(config, battery, now)
        }
        return when (config.mode) {
            RingMode.OFF -> BLACK.copyOf()
            RingMode.NOTIFICATIONS_ONLY -> if (music) musicOnly(config, audio) else BLACK.copyOf()
            RingMode.SOLID -> withMusic(solid(config.color), config, audio)
            RingMode.RAINBOW -> rainbow(config, now, audio)
            RingMode.BREATHE -> breathe(config, now, audio)
            RingMode.COMET -> comet(config, now, audio)
            RingMode.SPECTRUM -> spectrum(config, audio)
            RingMode.SPLIT -> withMusic(split(config), config, audio)
            RingMode.SPIN -> spin(config, now, audio)
            RingMode.POLICE -> police(config, now, audio)
            RingMode.FIRE -> fire(config, now, audio)
            RingMode.AURORA -> aurora(config, now, audio)
            RingMode.BATTERY -> batteryFrame(config, battery, now)
        }
    }

    // ---- music -----------------------------------------------------------

    private fun level(config: RingConfig, audio: AudioState): Float =
        audio.level.coerceIn(0f, 1f)

    /** Brightness multiplier for modes that are not inherently audio-shaped. */
    private fun musicEnv(config: RingConfig, audio: AudioState): Float {
        if (!config.music || !audio.hasSignal) return 1f
        val l = level(config, audio)
        val p = (l * 0.62f + audio.beat * 0.38f).coerceIn(0f, 1f)
        return (0.45f + 0.55f * p).coerceIn(0f, 1f)
    }

    private fun withMusic(colors: IntArray, config: RingConfig, audio: AudioState): IntArray {
        val env = musicEnv(config, audio)
        if (env >= 0.999f) return colors
        return IntArray(PIXELS) { scale(colors.getOrElse(it) { 0 }, env) }
    }

    private fun spectrum(config: RingConfig, audio: AudioState): IntArray {
        return IntArray(PIXELS) { i ->
            val v = audio.bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val base = Palettes.sample(config.palette, i / (PIXELS - 1f), config.color, config.color2)
            val boost = 1f + audio.beat * 0.25f
            scale(base, ((0.04f + 0.96f * v) * boost).coerceIn(0f, 1f))
        }
    }

    private fun musicOnly(config: RingConfig, audio: AudioState): IntArray {
        return IntArray(PIXELS) { i ->
            val band = audio.bands.getOrElse(i) { 0f }.coerceIn(0f, 1f)
            val v = (0.18f + 0.82f * (band * 0.7f + level(config, audio) * 0.3f)) *
                (1f + audio.beat * 0.2f)
            val base = Palettes.sample(config.palette, i / (PIXELS - 1f), config.color, config.color2)
            scale(base, v.coerceIn(0f, 1f))
        }
    }

    // ---- modes -----------------------------------------------------------

    private fun rainbow(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.15f, 4f)
        val spin = (now * 0.00012f * speed + audio.beatCount * 0.010f * speed) % 1f
        val env = musicEnv(config, audio)
        return IntArray(PIXELS) { i ->
            val c = Palettes.sample(config.palette, spin + i / PIXELS.toFloat(), config.color, config.color2)
            scale(c, env)
        }
    }

    private fun breathe(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val period = config.breatheMs.coerceIn(600, 12_000).toFloat()
        val phase = (now % period.toLong()) / period
        var env = 0.05f + 0.95f * (0.5f - 0.5f * cos(2.0 * PI * phase)).toFloat()
        if (config.music && audio.hasSignal) {
            // Music deepens the breath instead of replacing it.
            val l = level(config, audio)
            env = (env * 0.55f + 0.20f * l + 0.25f * audio.beat).coerceIn(0f, 1f)
        }
        return solid(scale(config.color, env))
    }

    private fun comet(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.15f, 4f)
        val steps = (now * speed / 140f).toInt() + audio.beatCount.toInt()
        val head = steps % PIXELS
        val env = musicEnv(config, audio)
        return IntArray(PIXELS) { i ->
            val distance = (head - i + PIXELS) % PIXELS
            val intensity = when (distance) {
                0 -> 1f
                1 -> 0.55f
                2 -> 0.25f
                3 -> 0.08f
                else -> 0f
            }
            scale(config.color, intensity * env)
        }
    }

    private fun split(config: RingConfig): IntArray = IntArray(PIXELS) { i ->
        if (i < PIXELS / 2) config.color else config.color2
    }

    private fun spin(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.15f, 4f)
        val step = (now * speed / 110f).toInt() + audio.beatCount.toInt()
        val spotA = step % PIXELS
        val spotB = (step + PIXELS / 2) % PIXELS
        val env = musicEnv(config, audio)
        return IntArray(PIXELS) { i ->
            val c = when (i) {
                spotA -> config.color
                spotB -> config.color2
                (spotA - 1 + PIXELS) % PIXELS -> scale(config.color, 0.35f)
                (spotB - 1 + PIXELS) % PIXELS -> scale(config.color2, 0.35f)
                else -> 0
            }
            scale(c, env)
        }
    }

    private fun police(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.2f, 3f)
        val phase = ((now * speed / 95L) % 6L).toInt()
        val red = 0xFFFF1818.toInt()
        val blue = 0xFF1855FF.toInt()
        val left = when (phase) {
            0, 1 -> red
            3, 4 -> blue
            else -> 0
        }
        val right = when (phase) {
            0, 1 -> 0
            3, 4 -> blue
            2 -> 0
            else -> 0
        }
        val env = if (config.music && audio.hasSignal) (0.35f + 0.65f * level(config, audio)) else 1f
        return IntArray(PIXELS) { i ->
            val c = if (i < PIXELS / 2) left else right
            if (c == 0) 0 else scale(c, env)
        }
    }

    private fun fire(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.2f, 3.5f)
        val env = musicEnv(config, audio)
        return IntArray(PIXELS) { i ->
            val phase = now * speed * 0.006f + i * 1.31f
            val flicker = 0.55f +
                0.45f * sin(phase.toDouble()).toFloat() *
                cos((phase * 1.73f).toDouble()).toFloat()
            val t = ((sin(phase.toDouble() * 0.41) * 0.5 + 0.5) + i / PIXELS.toFloat()) % 1f
            val base = Palettes.sample(config.palette, t.toFloat(), 0xFFFF3D00.toInt(), 0xFFFFCC00.toInt())
            scale(base, flicker.coerceIn(0.12f, 1f) * env)
        }
    }

    private fun aurora(config: RingConfig, now: Long, audio: AudioState): IntArray {
        val speed = config.speed.coerceIn(0.1f, 4f)
        val env = musicEnv(config, audio)
        return IntArray(PIXELS) { i ->
            val wave = sin(now * speed * 0.0018 + i * PI * 2.0 / PIXELS)
            val t = (wave * 0.16 + 0.5 + i / PIXELS.toFloat() * 0.35) % 1f
            val base = Palettes.sample(config.palette, t.toFloat(), config.color, config.color2)
            val bright = (0.45 + 0.55 * cos(now * speed * 0.0012 + i * 0.9)).toFloat()
            scale(base, (0.30f + 0.70f * bright) * env)
        }
    }

    private fun batteryFrame(config: RingConfig, battery: BatteryInfo, now: Long): IntArray {
        if (battery.level < 0) return BLACK.copyOf()
        val level = battery.level.coerceIn(0, 100)
        val color = when {
            level <= 15 -> 0xFFF87171.toInt()
            level <= 40 -> 0xFFFBBF24.toInt()
            else -> 0xFF4ADE80.toInt()
        }
        val filled = ((level / 100f) * PIXELS).toInt().coerceIn(0, PIXELS)
        return IntArray(PIXELS) { i ->
            when {
                i < filled -> color
                i == filled && battery.charging -> scale(color, 0.25f + 0.75f * sinPulse(now, 700))
                i == filled && level > 0 -> color
                else -> scale(color, 0.06f)
            }
        }
    }

    // ---- overlays --------------------------------------------------------

    /**
     * The "Effect" notification style: pulse whatever the ring is already
     * showing. When the base effect is dark (Off or Events-only with no
     * music), pulse the primary colour instead so the notification is still
     * visible.
     */
    private fun effectFrame(
        config: RingConfig,
        now: Long,
        battery: BatteryInfo,
        audio: AudioState,
        age: Long,
    ): IntArray {
        val env = (0.30f + 0.70f * sin(PI * ((age % 700L) / 700.0)).pow(2)).toFloat()
        val base = baseFrame(config, now, battery, audio)
        if (base.all { (it and 0xFFFFFF) == 0 }) return solid(scale(config.color, env))
        return IntArray(PIXELS) { scale(base[it], env) }
    }

    private fun overlayFrame(overlay: Overlay, now: Long): IntArray {
        val age = (now - overlay.startedAt).coerceAtLeast(0)
        return when (overlay.pattern) {
            NotifPattern.PULSE -> {
                val phase = (age % 520L) / 520f
                val env = sin(PI * phase).pow(2).toFloat()
                solid(scale(overlay.color, 0.06f + 0.94f * env))
            }

            NotifPattern.BLINK ->
                if ((age / 260L) % 2L == 0L) solid(overlay.color) else BLACK.copyOf()

            NotifPattern.BREATHE -> {
                val env = (0.5f - 0.5f * cos(2.0 * PI * age / 1700.0)).toFloat()
                solid(scale(overlay.color, 0.06f + 0.94f * env))
            }

            NotifPattern.SWEEP -> {
                val head = ((age / 105L) % PIXELS).toInt()
                IntArray(PIXELS) { i ->
                    val distance = (head - i + PIXELS) % PIXELS
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

    // ---- helpers ---------------------------------------------------------

    fun collapse(colors: IntArray): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        var total = 0f
        for (c in colors) {
            val cr = (c shr 16) and 0xFF
            val cg = (c shr 8) and 0xFF
            val cb = c and 0xFF
            if (cr == 0 && cg == 0 && cb == 0) continue
            val w = (cr * 0.3f + cg * 0.59f + cb * 0.11f) + 8f
            r += cr * w
            g += cg * w
            b += cb * w
            total += w
        }
        if (total <= 0f) return 0
        val outR = (r / total).toInt().coerceIn(0, 255)
        val outG = (g / total).toInt().coerceIn(0, 255)
        val outB = (b / total).toInt().coerceIn(0, 255)
        if (outR == 0 && outG == 0 && outB == 0) return 0
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    /** 0 -> instant, 1 -> very soft. */
    fun smoothingTauMs(config: RingConfig): Float {
        val s = config.smoothing.coerceIn(0f, 1f)
        return if (s < 0.02f) 0f else 25f + 235f * s
    }

    fun snapFrame(config: RingConfig, overlay: Overlay?): Boolean {
        if (config.mode == RingMode.POLICE) return true
        if (overlay != null && overlay.color != 0 &&
            (overlay.pattern == NotifPattern.BLINK || overlay.pattern == NotifPattern.SWEEP)
        ) {
            return true
        }
        return false
    }

    private fun solid(color: Int): IntArray = IntArray(PIXELS) { color }

    private fun sinPulse(now: Long, periodMs: Int): Float =
        (0.5f + 0.5f * sin(2.0 * PI * (now % periodMs) / periodMs)).toFloat()

    fun scale(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        if (f <= 0.004f) return 0
        val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        if (r == 0 && g == 0 && b == 0) return 0
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun luma(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 0.299f + g * 0.587f + b * 0.114f) / 255f
    }

    /** Kept for the colour picker; HSV with sane saturation. */
    fun hsv(hue: Float, saturation: Float, value: Float): Int =
        Color.HSVToColor(
            floatArrayOf(
                ((hue % 360f) + 360f) % 360f,
                saturation.coerceIn(0f, 1f),
                value.coerceIn(0f, 1f),
            )
        )

    @Suppress("unused")
    private fun absF(x: Float): Float = abs(x)
}
