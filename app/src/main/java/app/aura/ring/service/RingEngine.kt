package app.aura.ring.service

import android.os.SystemClock
import app.aura.ring.AppState
import app.aura.ring.RingConfig
import app.aura.ring.RingMode
import app.aura.ring.ScreenPolicy
import app.aura.ring.core.Effects
import app.aura.ring.core.FrameSmoother
import app.aura.ring.core.RingController
import app.aura.ring.core.ValueSmoother
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * Owns the frame clock.
 *
 * One coroutine renders frames, smooths them in time and writes them to the
 * controller. The frame rate drops to almost nothing when the ring is dark or
 * showing a static colour, and the loop sleeps on [AppState.wake] instead of
 * waking up for no reason.
 */
class RingEngine(private val controller: RingController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val frames = FrameSmoother()
    private val brightness = ValueSmoother(0f)
    private var previewAt = 0L
    private val blank = IntArray(Effects.PIXELS)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastFrameAt = 0L
            var boostUntil = SystemClock.elapsedRealtime() + BOOST_MS
            while (isActive) {
                val started = SystemClock.elapsedRealtime()
                val config = AppState.config.value
                var overlay = AppState.overlay.value
                val audio = AppState.audio.value
                val battery = AppState.battery.value
                val screenOn = AppState.screenOn.value

                if (overlay != null && started - overlay.startedAt > overlay.durationMs) {
                    AppState.clearOverlay()
                    overlay = null
                    boostUntil = started + BOOST_MS
                }

                val music = config.music && audio.hasSignal
                val event = overlay != null || music
                val lit = event || policyAllows(config, screenOn)
                val target = if (lit) {
                    Effects.render(config, started, battery, audio, overlay)
                } else {
                    blank
                }

                val dt = if (lastFrameAt == 0L) 16f else (started - lastFrameAt).toFloat()
                lastFrameAt = started

                val tau = Effects.smoothingTauMs(config)
                val snap = Effects.snapFrame(config, overlay)
                val frame = frames.smooth(target, dt, tau, snap)
                val level = brightness.smooth(
                    target = brightnessFor(config, screenOn, lit),
                    dtMs = dt,
                    tauMs = (tau * 0.6f).coerceAtLeast(45f),
                    snap = snap,
                )
                controller.show(frame, level.roundToInt())

                val previewNow = SystemClock.elapsedRealtime()
                if (previewNow - previewAt >= PREVIEW_MS) {
                    previewAt = previewNow
                    AppState.frame.value = frame.copyOf()
                }

                val interval = intervalFor(config, lit, event, started < boostUntil)
                if (interval >= SLEEP_THRESHOLD_MS) {
                    val woke = withTimeoutOrNull(interval) { AppState.wake.first() } != null
                    if (woke) boostUntil = SystemClock.elapsedRealtime() + BOOST_MS
                } else {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    delay((interval - elapsed).coerceAtLeast(1L))
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        frames.reset()
        brightness.reset(0f)
        AppState.frame.value = IntArray(Effects.PIXELS)
    }

    private fun policyAllows(config: RingConfig, screenOn: Boolean): Boolean = when (config.mode) {
        RingMode.OFF -> false
        else -> when (config.screenPolicy) {
            ScreenPolicy.ALWAYS -> true
            ScreenPolicy.SCREEN_ON -> screenOn
            ScreenPolicy.SCREEN_OFF -> !screenOn
        }
    }

    private fun brightnessFor(config: RingConfig, screenOn: Boolean, lit: Boolean): Float {
        if (!lit) return 0f
        var value = config.brightness.coerceIn(0, 255).toFloat()
        if (!screenOn && config.dimWhenScreenOff) value *= 0.4f
        return value
    }

    private fun intervalFor(
        config: RingConfig,
        lit: Boolean,
        event: Boolean,
        boosting: Boolean,
    ): Long {
        val base = if (AppState.powerSave.value && config.autoEco) {
            ECO_INTERVAL_MS
        } else {
            config.frameRate.intervalMs
        }
        return when {
            // Transitions, notifications and beat-driven frames are the only
            // things that benefit from a fast clock.
            event -> base.coerceAtMost(33L)
            boosting -> base.coerceAtMost(34L)
            !lit -> IDLE_INTERVAL_MS
            config.mode == RingMode.SOLID || config.mode == RingMode.SPLIT -> IDLE_INTERVAL_MS
            config.mode == RingMode.BATTERY -> 250L
            config.mode == RingMode.NOTIFICATIONS_ONLY -> IDLE_INTERVAL_MS
            else -> base
        }
    }

    companion object {
        private const val PREVIEW_MS = 40L
        private const val IDLE_INTERVAL_MS = 750L
        private const val ECO_INTERVAL_MS = 66L
        private const val SLEEP_THRESHOLD_MS = 120L
        private const val BOOST_MS = 700L
    }
}
