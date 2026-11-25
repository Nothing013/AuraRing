package app.aura.ring.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifPattern
import app.aura.ring.NotifStore
import app.aura.ring.RingMode
import app.aura.ring.service.RingService

/**
 * Automation entry point. Every broadcast is accepted by adb too:
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
        val app = context.applicationContext

        when (action) {
            ACTION_SET -> {
                val color = readColor(intent, "hex", "color_hex", "color")
                val color2 = readColor(intent, "hex2", "color2_hex", "color2")
                val mode = intent.getStringExtra("mode")?.let { raw ->
                    runCatching { RingMode.valueOf(raw.uppercase().replace(' ', '_')) }.getOrNull()
                }
                val brightness = intent.intExtra("brightness")?.takeIf { it in 0..255 }
                val speed = intent.floatExtra("speed")?.takeIf { it > 0f }
                val sensitivity = intent.floatExtra("sensitivity")?.takeIf { it > 0f }
                val music = intent.boolExtra("music")
                val notifications = intent.boolExtra("notifications")

                AppState.update { config ->
                    config.copy(
                        mode = mode ?: config.mode,
                        color = color ?: config.color,
                        color2 = color2 ?: config.color2,
                        brightness = brightness ?: config.brightness,
                        speed = speed ?: config.speed,
                        sensitivity = sensitivity ?: config.sensitivity,
                        music = music ?: config.music,
                        notifications = notifications ?: config.notifications,
                    )
                }
                ensureRunning(app)
            }

            ACTION_BRIGHTNESS -> {
                val value = intent.intExtra("brightness")
                if (value != null && value in 0..255) {
                    AppState.update { it.copy(brightness = value) }
                }
                ensureRunning(app)
            }

            ACTION_PULSE -> {
                val color = readColor(intent, "hex", "color_hex", "color") ?: 0xFF33E1FF.toInt()
                val pattern = intent.getStringExtra("pattern")?.let { raw ->
                    runCatching { NotifPattern.valueOf(raw.uppercase()) }.getOrNull()
                } ?: NotifPattern.PULSE
                val duration = intent.intExtra("duration")?.coerceIn(1, 30) ?: 4

                ensureService(
                    app,
                    Intent(app, RingService::class.java)
                        .setAction(RingService.ACTION_FLASH)
                        .putExtra(RingService.EXTRA_COLOR, color)
                        .putExtra(RingService.EXTRA_PATTERN, pattern.name)
                        .putExtra(RingService.EXTRA_DURATION, duration),
                )
            }

            ACTION_RECONNECT -> ensureService(
                app,
                Intent(app, RingService::class.java).setAction(RingService.ACTION_RECONNECT),
            )

            ACTION_OFF, ACTION_STOP -> {
                AppState.setRunning(false)
                ensureService(
                    app,
                    Intent(app, RingService::class.java).setAction(RingService.ACTION_STOP),
                )
            }

            ACTION_TOGGLE -> {
                if (AppState.running.value) {
                    AppState.setRunning(false)
                    ensureService(
                        app,
                        Intent(app, RingService::class.java).setAction(RingService.ACTION_STOP),
                    )
                } else {
                    ensureRunning(app)
                }
            }
        }
    }

    private fun Intent.intExtra(name: String): Int? =
        if (hasExtra(name)) getIntExtra(name, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE } else null

    private fun Intent.floatExtra(name: String): Float? =
        if (hasExtra(name)) getFloatExtra(name, Float.NaN).takeIf { !it.isNaN() } else null

    private fun Intent.boolExtra(name: String): Boolean? =
        if (hasExtra(name)) getBooleanExtra(name, false) else null

    private fun readColor(intent: Intent, vararg keys: String): Int? {
        for (key in keys) {
            val hex = intent.getStringExtra(key) ?: continue
            return runCatching {
                if (hex.startsWith("#") || hex.length > 7) Color.parseColor(hex)
                else Color.parseColor("#$hex")
            }.getOrNull()
        }
        return null
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
        const val ACTION_RECONNECT = "app.aura.ring.RECONNECT"
    }
}
