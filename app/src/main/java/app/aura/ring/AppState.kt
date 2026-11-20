package app.aura.ring

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class RingMode(val label: String, val blurb: String) {
    OFF("Off", "Ring is dark"),
    SOLID("Solid", "One steady colour"),
    RAINBOW("Rainbow", "Hue rotates around the ring"),
    BREATHE("Breathe", "Slow pulse in and out"),
    COMET("Comet", "A bright head with a fading tail"),
    SPECTRUM("Spectrum", "Eight audio bands, eight pixels"),
    SPLIT("Split", "Two complementary colours on opposite halves"),
    SPIN("Spin", "Dual orbiting spots around the ring"),
    POLICE("Police", "Red and blue alternating emergency strobe"),
    FIRE("Fire", "Warm organic flickering embers"),
    AURORA("Aurora", "Flowing nordic color wave"),
    BATTERY("Battery", "Battery level as a ring arc"),
}

enum class NotifPattern(val label: String) {
    PULSE("Pulse"),
    SWEEP("Sweep"),
    BLINK("Blink"),
    BREATHE("Breathe"),
}

data class RingConfig(
    val mode: RingMode = RingMode.SOLID,
    val color: Int = 0xFF33E1FF.toInt(),
    val color2: Int = 0xFFFF2D95.toInt(),
    val brightness: Int = 200,
    val speed: Float = 1f,
    val breatheMs: Int = 2600,
    val music: Boolean = false,
    val notifications: Boolean = true,
    val chargingArc: Boolean = true,
    val screenOffDim: Boolean = true,
    val autoStart: Boolean = true,
)

data class BatteryInfo(
    val level: Int = -1,
    val charging: Boolean = false,
    val plugged: Boolean = false,
)

data class NotifApp(
    val pkg: String,
    val enabled: Boolean = false,
    val color: Int = 0xFF33E1FF.toInt(),
    val pattern: NotifPattern = NotifPattern.PULSE,
)

/**
 * A short effect that temporarily overrides the base mode. Timestamps use
 * SystemClock.elapsedRealtime() so they are comparable everywhere.
 */
data class Overlay(
    val color: Int,
    val pattern: NotifPattern,
    val durationMs: Long,
    val startedAt: Long,
)

object AppState {
    private var prefs: SharedPreferences? = null

    val config = MutableStateFlow(RingConfig())
    val running = MutableStateFlow(false)
    val backend = MutableStateFlow("Starting")
    val battery = MutableStateFlow(BatteryInfo())
    val screenOn = MutableStateFlow(true)
    val bands = MutableStateFlow(FloatArray(8))
    val preview = MutableStateFlow(IntArray(8) { 0xFF000000.toInt() })
    val overlay = MutableStateFlow<Overlay?>(null)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("aura", Context.MODE_PRIVATE)
        prefs = p
        config.value = RingConfig(
            mode = RingMode.entries.getOrElse(p.getInt("mode", RingMode.SOLID.ordinal)) { RingMode.SOLID },
            color = p.getInt("color", 0xFF33E1FF.toInt()),
            color2 = p.getInt("color2", 0xFFFF2D95.toInt()),
            brightness = p.getInt("brightness", 200),
            speed = p.getFloat("speed", 1f),
            breatheMs = p.getInt("breathe_ms", 2600),
            music = p.getBoolean("music", false),
            notifications = p.getBoolean("notifications", true),
            chargingArc = p.getBoolean("charging_arc", true),
            screenOffDim = p.getBoolean("screen_off_dim", true),
            autoStart = p.getBoolean("auto_start", true),
        )
        running.value = p.getBoolean("running", false)
    }

    fun update(block: (RingConfig) -> RingConfig) {
        config.value = block(config.value)
        saveConfig()
    }

    private fun saveConfig() {
        val p = prefs ?: return
        val c = config.value
        p.edit()
            .putInt("mode", c.mode.ordinal)
            .putInt("color", c.color)
            .putInt("color2", c.color2)
            .putInt("brightness", c.brightness)
            .putFloat("speed", c.speed)
            .putInt("breathe_ms", c.breatheMs)
            .putBoolean("music", c.music)
            .putBoolean("notifications", c.notifications)
            .putBoolean("charging_arc", c.chargingArc)
            .putBoolean("screen_off_dim", c.screenOffDim)
            .putBoolean("auto_start", c.autoStart)
            .apply()
    }

    fun setRunning(value: Boolean) {
        running.value = value
        prefs?.edit()?.putBoolean("running", value)?.apply()
    }

    fun getString(key: String, fallback: String? = null): String? =
        prefs?.getString(key, fallback)

    fun putString(key: String, value: String?) {
        prefs?.edit()?.putString(key, value)?.apply()
    }

    fun getInt(key: String, fallback: Int): Int = prefs?.getInt(key, fallback) ?: fallback

    fun putInt(key: String, value: Int) {
        prefs?.edit()?.putInt(key, value)?.apply()
    }
}

object NotifStore {
    private val _apps = MutableStateFlow<Map<String, NotifApp>>(emptyMap())
    val apps: StateFlow<Map<String, NotifApp>> = _apps

    fun load(context: Context) {
        val raw = AppState.getString("notif_apps") ?: return
        try {
            val arr = JSONArray(raw)
            val map = LinkedHashMap<String, NotifApp>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.optString("pkg")
                if (pkg.isBlank()) continue
                map[pkg] = NotifApp(
                    pkg = pkg,
                    enabled = o.optBoolean("enabled", false),
                    color = o.optInt("color", 0xFF33E1FF.toInt()),
                    pattern = runCatching {
                        NotifPattern.valueOf(o.optString("pattern", NotifPattern.PULSE.name))
                    }.getOrDefault(NotifPattern.PULSE),
                )
            }
            _apps.value = map
        } catch (_: Throwable) {
            _apps.value = emptyMap()
        }
    }

    fun get(pkg: String): NotifApp = _apps.value[pkg] ?: NotifApp(pkg)

    fun update(pkg: String, block: (NotifApp) -> NotifApp) {
        val current = get(pkg)
        val next = block(current).copy(pkg = pkg)
        _apps.value = _apps.value + (pkg to next)
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (app in _apps.value.values) {
            arr.put(
                JSONObject()
                    .put("pkg", app.pkg)
                    .put("enabled", app.enabled)
                    .put("color", app.color)
                    .put("pattern", app.pattern.name)
            )
        }
        AppState.putString("notif_apps", arr.toString())
    }
}
