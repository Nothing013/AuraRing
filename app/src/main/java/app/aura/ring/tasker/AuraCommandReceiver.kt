package app.aura.ring.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifStore
import app.aura.ring.NotifPattern
import app.aura.ring.RingMode
import app.aura.ring.service.RingService

/**
 * Automation entry point. Every broadcast is also accepted by adb:
 *
 *   am broadcast -a app.aura.ring.SET \
 *       -n app.aura.ring/.tasker.AuraCommandReceiver \
 *       --es hex "#33E1FF" --es mode SOLID --ei brightness 150
 */
class AuraCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        AppState.init(context)
        NotifStore.load(context)
        val ctx = context.applicationContext

        when (action) {
            ACTION_SET -> {
                val color = readColor(intent)
                val mode = intent.getStringExtra("mode")?.let { raw ->
                    runCatching { RingMode.valueOf(raw.uppercase()) }.getOrNull()
                }
                val brightness = intent.takeIf { it.hasExtra("brightness") }
                    ?.getIntExtra("brightness", -1)
                    ?.takeIf { it in 0..255 }
                val speed = intent.takeIf { it.hasExtra("speed") }
                    ?.getFloatExtra("speed", -1f)
                    ?.takeIf { it > 0f }

                AppState.update { config ->
                    config.copy(
                        mode = mode ?: config.mode,
                        color = color ?: config.color,
                        brightness = brightness ?: config.brightness,
                        speed = speed ?: config.speed,
                    )
                }
                ensureRunning(ctx)
            }

            ACTION_BRIGHTNESS -> {
                val value = intent.getIntExtra("brightness", -1)
                if (value in 0..255) {
                    AppState.update { it.copy(brightness = value) }
                }
                ensureRunning(ctx)
            }

            ACTION_PULSE -> {
                val color = readColor(intent) ?: 0xFF33E1FF.toInt()
                val pattern = intent.getStringExtra("pattern")?.let { raw ->
                    runCatching { NotifPattern.valueOf(raw.uppercase()) }.getOrNull()
                } ?: NotifPattern.PULSE
                val duration = intent.getIntExtra("duration", 4).coerceIn(1, 30)

                val flash = Intent(ctx, RingService::class.java)
                    .setAction(RingService.ACTION_FLASH)
                    .putExtra(RingService.EXTRA_COLOR, color)
                    .putExtra(RingService.EXTRA_PATTERN, pattern.name)
                    .putExtra(RingService.EXTRA_DURATION, duration)
                ensureService(ctx, flash)
            }

            ACTION_OFF, ACTION_STOP -> {
                AppState.setRunning(false)
                ensureService(ctx, Intent(ctx, RingService::class.java).setAction(RingService.ACTION_STOP))
            }

            ACTION_TOGGLE -> {
                if (AppState.running.value) {
                    AppState.setRunning(false)
                    ensureService(
                        ctx,
                        Intent(ctx, RingService::class.java).setAction(RingService.ACTION_STOP)
                    )
                } else {
                    ensureRunning(ctx)
                }
            }

            else -> Unit
        }
    }

    private fun readColor(intent: Intent): Int? {
        val hex = intent.getStringExtra("hex") ?: intent.getStringExtra("color_hex")
        if (hex != null) {
            return runCatching {
                if (hex.startsWith("#") || hex.length > 7) Color.parseColor(hex)
                else Color.parseColor("#$hex")
            }.getOrNull()
        }
        return if (intent.hasExtra("color")) intent.getIntExtra("color", 0) else null
    }

    private fun ensureRunning(context: Context) {
        ensureService(context, Intent(context, RingService::class.java))
    }

    private fun ensureService(context: Context, intent: Intent) {
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }
    }

    companion object {
        const val ACTION_SET = "app.aura.ring.SET"
        const val ACTION_OFF = "app.aura.ring.OFF"
        const val ACTION_STOP = "app.aura.ring.STOP"
        const val ACTION_PULSE = "app.aura.ring.PULSE"
        const val ACTION_BRIGHTNESS = "app.aura.ring.BRIGHTNESS"
        const val ACTION_TOGGLE = "app.aura.ring.TOGGLE"
    }
}
