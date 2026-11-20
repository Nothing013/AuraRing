package app.aura.ring.service

import android.os.SystemClock
import app.aura.ring.AppState
import app.aura.ring.RingMode
import app.aura.ring.core.RingController
import app.aura.ring.core.Effects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class RingEngine(private val controller: RingController) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var yielded = false

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastPreview = 0L
            while (isActive) {
                val config = AppState.config.value
                val now = SystemClock.elapsedRealtime()

                if (config.yieldToCamera && AppState.cameraInUse.value) {
                    if (!yielded) {
                        yielded = true
                        controller.off()
                        AppState.preview.value = IntArray(8)
                    }
                    delay(120)
                    continue
                }
                if (yielded) {
                    yielded = false
                    controller.invalidate()
                }

                val overlay = AppState.overlay.value
                if (overlay != null && now - overlay.startedAt > overlay.durationMs) {
                    AppState.overlay.value = null
                }

                val colors = Effects.render(
                    config = config,
                    nowMs = now,
                    battery = AppState.battery.value,
                    bands = AppState.bands.value,
                    overlay = AppState.overlay.value,
                    perPixel = controller.supportsPixels,
                )
                controller.show(colors, brightnessFor(config, now))

                if (now - lastPreview >= 50) {
                    AppState.preview.value = colors
                    lastPreview = now
                }

                val interval = when {
                    !AppState.screenOn.value -> 110L
                    AppState.overlay.value != null -> 33L
                    config.mode == RingMode.SOLID -> 150L
                    config.mode == RingMode.BATTERY -> 250L
                    else -> 34L
                }
                delay(interval)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun brightnessFor(config: app.aura.ring.RingConfig, now: Long): Int {
        var value = config.brightness.coerceIn(0, 255)
        if (!AppState.screenOn.value && config.screenOffDim) {
            value = (value * 0.35f).toInt().coerceIn(0, 255)
        }
        return value
    }
}
