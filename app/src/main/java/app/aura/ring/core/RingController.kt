package app.aura.ring.core

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.aura.ring.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the camera ring.
 *
 * Root is preferred because it exposes all eight RGB pixels. Without root, the
 * HyperOS binder is used and the frame is collapsed to a single blended colour.
 *
 * The controller never trusts the chip: every write failure triggers a bounded
 * reconnect, and the sysfs path is discovered instead of hard-coded.
 */
class RingController(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("aura", Context.MODE_PRIVATE)

    private var root: RootShell? = null
    private var rootReady = false
    private var binderReady = false
    private var sys = DEFAULT_SYS

    private var originalStrip = 2
    private var myGeneration = 0L
    private var lastReconnectMs = 0L

    private var lastColors: IntArray? = null
    private var lastBrightness = -1
    private var lastUniform = false
    private var lastOff = false
    private var lastBinderColor = Int.MIN_VALUE

    val supportsPixels: Boolean get() = rootReady

    val backendName: String
        get() = when {
            rootReady -> "Root"
            binderReady -> "Shizuku"
            else -> "None"
        }

    suspend fun init(): String = withContext(Dispatchers.IO) {
        myGeneration = GENERATION.incrementAndGet()
        readOriginalStrip()

        val shell = RootShell.start()
        if (shell != null) {
            val node = findNode(shell)
            if (node != null) {
                root = shell
                sys = node
                rootReady = true
                binderReady = false
                BinderRing.invalidate()
                shell.exec("echo 1 > $sys/hwen")
                // Aura owns the controller while it runs; this stops HyperOS
                // scenes from overwriting frames. Restored on stop.
                shell.exec("settings put global settings_strip_light_enable 0")
                invalidate()
                AppState.backend.value = "Root"
                Log.d(TAG, "root backend, node=$node")
                return@withContext "Root"
            }
            shell.close()
        }

        if (BinderRing.permitted()) {
            binderReady = true
            rootReady = false
            BinderRing.shell(
                arrayOf("settings", "put", "global", "settings_strip_light_enable", "2")
            )
            invalidate()
            AppState.backend.value = "Shizuku"
            return@withContext "Shizuku"
        }

        rootReady = false
        binderReady = false
        AppState.backend.value = "None"
        "None"
    }

    suspend fun show(colors: IntArray, brightness: Int) {
        if (rootReady) {
            rootShow(colors, brightness)
        } else if (binderReady) {
            binderShow(colors, brightness)
        }
    }

    private suspend fun rootShow(colors: IntArray, brightness: Int) {
        val shell = root
        if (shell == null || !shell.alive) {
            reconnect("root shell died")
            return
        }

        val b = brightness.coerceIn(0, 255)
        val empty = colors.all { (it and 0xFFFFFF) == 0 }
        if (b == 0 || empty) {
            if (lastOff) return
            val result = shell.exec("echo 0 > $sys/effect")
            if (result.code == 0) {
                lastOff = true
                lastColors = null
                lastBrightness = 0
                lastUniform = false
            } else {
                reconnect("effect write failed")
            }
            return
        }
        lastOff = false

        val uniform = colors.all { it == colors[0] }
        val argb = colors[0] and 0xFFFFFF
        if (uniform) {
            val cached = lastColors
            if (lastUniform && cached != null && (cached[0] and 0xFFFFFF) == argb && b == lastBrightness) return
        } else {
            val cached = lastColors
            if (!lastUniform && cached != null && colors.contentEquals(cached) && b == lastBrightness) return
        }

        val command = StringBuilder("S=").append(sys).append("; ")
        if (b != lastBrightness) command.append("echo ").append(b).append(" > \$S/brightness; ")

        if (uniform) {
            command.append("echo '0xa1 0x00' > \$S/reg; ")
            command.append("echo '0xa7 0x66' > \$S/reg; ")
            command.append("echo '0xa8 0x").append(hex((argb shr 16) and 0xFF)).append("' > \$S/reg; ")
            command.append("echo '0xa9 0x").append(hex((argb shr 8) and 0xFF)).append("' > \$S/reg; ")
            command.append("echo '0xaa 0x").append(hex(argb and 0xFF)).append("' > \$S/reg; ")
            command.append("echo '0xab 0xff' > \$S/reg; ")
            command.append("echo '0xac 0x00' > \$S/reg")
        } else {
            for (i in 0 until 8) {
                command.append("echo '").append(i).append(' ')
                    .append(hexRgb(colors.getOrElse(i) { 0 }))
                    .append("' > \$S/rgbcolor; ")
            }
            command.append("true")
        }

        val result = shell.exec(command.toString())
        if (result.code == 0) {
            lastColors = colors.copyOf()
            lastBrightness = b
            lastUniform = uniform
        } else {
            reconnect("frame write failed (exit ${result.code})")
        }
    }

    private fun binderShow(colors: IntArray, brightness: Int) {
        if (!BinderRing.permitted()) {
            binderReady = false
            AppState.backend.value = "None"
            return
        }
        val blended = Effects.collapse(colors)
        val luma = Effects.luma(blended)
        val out = if (luma <= 0.002f || brightness <= 0) {
            0
        } else {
            val scaled = Effects.scale(blended, brightness / 255f)
            if (scaled == 0) 0 else scaled or 0xFF000000.toInt()
        }
        if (out == lastBinderColor) return
        if (BinderRing.setColor(out)) {
            lastBinderColor = out
        } else {
            BinderRing.invalidate()
            lastBinderColor = Int.MIN_VALUE
        }
    }

    suspend fun off() {
        if (rootReady) {
            root?.exec("echo 0 > $sys/effect")
            lastOff = true
            lastColors = null
            lastBrightness = 0
        }
        if (binderReady) {
            BinderRing.setColor(0)
            lastBinderColor = 0
        }
    }

    suspend fun hardwareReset(): Boolean {
        val shell = root ?: return false
        val result = shell.exec("echo 1 > $sys/hwen")
        invalidate()
        return result.code == 0
    }

    /** Forget the cached hardware state so the next frame rewrites everything. */
    fun invalidate() {
        lastColors = null
        lastBrightness = -1
        lastUniform = false
        lastOff = false
        lastBinderColor = Int.MIN_VALUE
    }

    /** Full backend restart, used by the reconnect button and on write errors. */
    suspend fun reconnect(reason: String = "manual") {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReconnectMs < 3_000) return
        lastReconnectMs = now
        Log.w(TAG, "reconnecting: $reason")
        releaseBackend(restoreStrip = false)
        init()
    }

    suspend fun setSystemStrip(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (on) 2 else 0
        when {
            rootReady -> root?.exec("settings put global settings_strip_light_enable $value")?.code == 0
            binderReady -> BinderRing.shell(
                arrayOf("settings", "put", "global", "settings_strip_light_enable", value.toString())
            )
            else -> false
        }
    }

    /** Called when the service stops: hand the ring back to HyperOS. */
    suspend fun restore() = withContext(Dispatchers.IO) {
        if (GENERATION.get() != myGeneration) {
            // A newer instance already owns the hardware; do not clobber it.
            return@withContext
        }
        try {
            if (rootReady) {
                root?.exec("echo 0 > $sys/effect")
                if (originalStrip >= 0) {
                    root?.exec("settings put global settings_strip_light_enable $originalStrip")
                }
            } else if (binderReady) {
                BinderRing.setColor(0)
                BinderRing.resetScene()
                if (originalStrip >= 0) {
                    BinderRing.shell(
                        arrayOf(
                            "settings", "put", "global",
                            "settings_strip_light_enable", originalStrip.toString(),
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "restore failed", t)
        } finally {
            releaseBackend(restoreStrip = false)
        }
    }

    private fun releaseBackend(@Suppress("UNUSED_PARAMETER") restoreStrip: Boolean) {
        root?.close()
        root = null
        rootReady = false
        if (binderReady) BinderRing.invalidate()
        binderReady = false
        invalidate()
    }

    private suspend fun readOriginalStrip() {
        val live = runCatching {
            Settings.Global.getInt(appContext.contentResolver, "settings_strip_light_enable", 2)
        }.getOrDefault(2)
        val stored = prefs.getInt("original_strip", -1)
        // If a previous run died while owning the chip, the live value is 0.
        originalStrip = if (live == 0 && stored >= 0) stored else live
        if (live != 0) prefs.edit().putInt("original_strip", live).apply()
    }

    private suspend fun findNode(shell: RootShell): String? {
        val result = shell.exec(
            "for d in $SYS_CANDIDATES; do " +
                "if [ -w \"\$d/brightness\" ]; then echo \"\$d\"; break; fi; " +
                "done"
        )
        return result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("/sys/") }
    }

    private fun hex(value: Int): String = HEX[value and 0xFF]

    private fun hexRgb(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return HEX[r] + HEX[g] + HEX[b]
    }

    companion object {
        private const val TAG = "AuraRing"
        private const val DEFAULT_SYS = "/sys/class/leds/aw21024_led"
        private const val SYS_CANDIDATES =
            "/sys/class/leds/aw21024_led /sys/class/leds/aw21024 /sys/class/leds/aw21024_leds " +
                "/sys/class/leds/leds_aw21024 /sys/class/leds/color21024"

        private val GENERATION = AtomicLong(0)
        private val HEX = Array(256) { "%02x".format(it) }
    }
}
