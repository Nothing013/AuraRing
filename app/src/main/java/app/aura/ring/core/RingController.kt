package app.aura.ring.core

import android.content.Context
import android.provider.Settings
import app.aura.ring.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the camera ring. Root is preferred because it exposes all eight RGB
 * pixels; the HyperOS Binder is the fallback and only supports one colour for
 * the whole ring.
 */
class RingController(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var root: RootShell? = null
    private var rootReady = false
    private var binderReady = false

    private var originalStrip = 2
    private var originalLamp = 175

    private var lastColors: IntArray? = null
    private var lastBrightness = -1
    private var lastBinderColor = Int.MIN_VALUE
    private var lastBinderBrightness = -1
    private var lampJob: Job? = null

    val supportsPixels: Boolean get() = rootReady
    val backendName: String
        get() = when {
            rootReady -> "Root"
            binderReady -> "Shizuku"
            else -> "None"
        }

    suspend fun init(): String {
        originalStrip = Settings.Global.getInt(
            appContext.contentResolver, "settings_strip_light_enable", 2
        )
        originalLamp = Settings.Global.getInt(
            appContext.contentResolver, "lamp_effect_brightness", 175
        )

        val shell = RootShell.start()
        if (shell != null) {
            root = shell
            // Aura owns the controller while it runs; this releases the
            // framework's grip so notifications/music cannot stomp our frames.
            shell.exec("settings put global settings_strip_light_enable 0")
            shell.exec("echo 1 > $SYS/hwen")
            rootReady = true
            binderReady = false
            AppState.backend.value = "Root"
            return "Root"
        }

        if (BinderRing.permitted()) {
            binderReady = true
            rootReady = false
            BinderRing.shell(
                arrayOf("settings", "put", "global", "settings_strip_light_enable", "2")
            )
            AppState.backend.value = "Shizuku"
            return "Shizuku"
        }

        rootReady = false
        binderReady = false
        AppState.backend.value = "None"
        return "None"
    }

    suspend fun show(colors: IntArray, brightness: Int) {
        if (rootReady) rootShow(colors, brightness)
        else if (binderReady) binderShow(colors, brightness)
    }

    private suspend fun rootShow(colors: IntArray, brightness: Int) {
        val shell = root ?: return
        val b = brightness.coerceIn(0, 255)

        if (b == 0) {
            val r = shell.exec("echo 0 > $SYS/effect")
            if (r.code == 0) {
                lastColors = IntArray(8)
                lastBrightness = 0
            }
            return
        }

        val same = colors.all { it == colors[0] }
        if (same && colors[0] == 0 && b == lastBrightness) {
            return
        }
        if (!same && colors.contentEquals(lastColors) && b == lastBrightness) {
            return
        }
        if (same && lastColors != null && lastColors!!.all { it == colors[0] } && b == lastBrightness) {
            return
        }

        val cmd = StringBuilder("S=$SYS; ")
        if (b != lastBrightness) {
            cmd.append("echo $b > \$S/brightness; ")
        }

        if (same) {
            val c = colors[0]
            if (c == 0) {
                cmd.append("echo 0 > \$S/effect")
            } else {
                cmd.append("echo '0x7a 0x00' > \$S/reg; ")
                cmd.append("echo '0xa8 ${hexByte(c shr 16)}' > \$S/reg; ")
                cmd.append("echo '0xa9 ${hexByte(c shr 8)}' > \$S/reg; ")
                cmd.append("echo '0xaa ${hexByte(c)}' > \$S/reg; ")
                cmd.append("echo '0xab 0xff' > \$S/reg; ")
                cmd.append("echo '0xac 0x00' > \$S/reg")
            }
        } else {
            for (i in 0 until 8) {
                cmd.append("echo '$i ${hexRgb(colors[i])}' > \$S/rgbcolor; ")
            }
            cmd.append("true")
        }

        val result = shell.exec(cmd.toString())
        if (result.code == 0) {
            lastColors = colors.copyOf()
            lastBrightness = b
        }
    }

    private fun binderShow(colors: IntArray, brightness: Int) {
        var best = 0
        var bestLuma = -1f
        for (c in colors) {
            val l = 0.299f * ((c shr 16) and 0xFF) +
                0.587f * ((c shr 8) and 0xFF) +
                0.114f * (c and 0xFF)
            if (l > bestLuma) {
                bestLuma = l
                best = c
            }
        }
        val scaled = scale(best, brightness / 255f)
        val out = if (bestLuma <= 0.5f) 0 else (scaled or 0xFF000000.toInt())
        if (out == lastBinderColor && brightness == lastBinderBrightness) return
        if (BinderRing.setColor(out)) {
            lastBinderColor = out
            lastBinderBrightness = brightness
        }
    }

    suspend fun off() {
        if (rootReady) {
            root?.exec("echo 0 > $SYS/effect")
            lastColors = IntArray(8)
            lastBrightness = 0
        } else if (binderReady) {
            BinderRing.setColor(0)
            lastBinderColor = 0
        }
    }

    suspend fun hardwareReset(): Boolean {
        val shell = root ?: return false
        val result = shell.exec("echo 1 > $SYS/hwen")
        lastColors = null
        lastBrightness = -1
        return result.code == 0
    }

    suspend fun registerDump(): String {
        val shell = root ?: return "Root is not available"
        return shell.exec("cat $SYS/reg 2>&1").output
    }

    /** Global HyperOS strip switch (0 = off, 2 = on). */
    suspend fun setSystemStrip(on: Boolean): Boolean {
        val value = if (on) 2 else 0
        return if (rootReady) {
            root?.exec("settings put global settings_strip_light_enable $value")?.code == 0
        } else if (binderReady) {
            BinderRing.shell(
                arrayOf("settings", "put", "global", "settings_strip_light_enable", value.toString())
            )
        } else {
            false
        }
    }

    /**
     * HyperOS brightness is applied through an alpha byte and always shows a
     * five-second white preview. We only use it when the user explicitly asks
     * for it; normal brightness is scaled in software.
     */
    fun setSystemBrightness(value: Int) {
        val v = value.coerceIn(80, 255)
        lampJob?.cancel()
        lampJob = scope.launch {
            val ok = if (rootReady) {
                root?.exec("settings put global lamp_effect_brightness $v")?.code == 0
            } else {
                BinderRing.shell(
                    arrayOf(
                        "settings", "put", "global", "lamp_effect_brightness", v.toString()
                    )
                )
            }
            if (ok) {
                delay(5_200)
                // The preview timer turns the ring off; put our colour back.
                lastBinderColor = Int.MIN_VALUE
                lastBinderBrightness = -1
                lastColors = null
                lastBrightness = -1
            }
        }
    }

    /** Called when the service stops: hand the ring back to HyperOS. */
    suspend fun restore() {
        if (rootReady) {
            root?.exec("echo 0 > $SYS/effect")
            root?.exec("settings put global settings_strip_light_enable $originalStrip")
            root?.close()
            root = null
            rootReady = false
        } else if (binderReady) {
            BinderRing.setColor(0)
            if (originalStrip != 2) {
                BinderRing.shell(
                    arrayOf(
                        "settings", "put", "global",
                        "settings_strip_light_enable", originalStrip.toString()
                    )
                )
            }
            binderReady = false
        }
        lastColors = null
        lastBrightness = -1
        lastBinderColor = Int.MIN_VALUE
    }

    private fun scale(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        val r = (((color shr 16) and 0xFF) * f).toInt().coerceIn(0, 255)
        val g = (((color shr 8) and 0xFF) * f).toInt().coerceIn(0, 255)
        val b = ((color and 0xFF) * f).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    private fun hexByte(value: Int): String =
        "0x%02x".format(value and 0xFF)

    private fun hexRgb(color: Int): String =
        "%02x%02x%02x".format(
            (color shr 16) and 0xFF,
            (color shr 8) and 0xFF,
            color and 0xFF,
        )

    companion object {
        private const val SYS = "/sys/class/leds/aw21024_led"
    }
}
