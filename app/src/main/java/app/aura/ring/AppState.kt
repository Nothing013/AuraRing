package app.aura.ring

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import app.aura.ring.audio.AudioState
import app.aura.ring.core.Palettes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the ring can do. Saved modes are matched by name; the [legacy]
 * list preserves the 0.1 ordinal order so an in-place upgrade keeps the mode
 * the user had selected. New modes are appended to the enum.
 */
enum class RingMode(val label: String, val blurb: String) {
    SOLID("Solid", "One steady colour"),
    RAINBOW("Rainbow", "Palette colours flowing around the ring"),
    BREATHE("Breathe", "A slow, calm fade in and out"),
    COMET("Comet", "A bright head with a fading tail"),
    SPECTRUM("Spectrum", "The music split into eight bands"),
    SPLIT("Split", "Two colours on opposite halves"),
    SPIN("Spin", "Two spots orbiting the ring"),
    POLICE("Police", "Red and blue emergency strobe"),
    FIRE("Fire", "Warm, restlessly flickering embers"),
    AURORA("Aurora", "A slow wave of nordic light"),
    BATTERY("Battery", "Battery level drawn as an arc"),
    NOTIFICATIONS_ONLY("Events only", "Dark until a notification, automation or music"),
    OFF("Off", "Dark; notifications still flash");

    companion object {
        /** 0.1 stored modes by ordinal, in this exact order. */
        val legacy = listOf(
            OFF, SOLID, RAINBOW, BREATHE, COMET, SPECTRUM,
            SPLIT, SPIN, POLICE, FIRE, AURORA, BATTERY,
        )
    }
}

enum class NotifPattern(val label: String) {
    PULSE("Pulse"),
    SWEEP("Sweep"),
    BLINK("Blink"),
    BREATHE("Breathe"),
}

enum class NotifStyle(val label: String, val blurb: String) {
    OFF("Off", "Notifications never light the ring"),
    FLASH("Flash", "A short coloured pattern per app"),
    EFFECT("Effect", "Run the current ring effect for a while"),
}

enum class ScreenPolicy(val label: String, val blurb: String) {
    ALWAYS("Always", "Keep the effect running all the time"),
    SCREEN_ON("Screen on", "Pause while the screen is off"),
    SCREEN_OFF("Screen off", "Only light the ring while the screen is off"),
}

enum class FrameRate(val label: String, val intervalMs: Long) {
    SMOOTH("Smooth", 25L),
    BALANCED("Balanced", 34L),
    ECO("Eco", 60L),
}

enum class AudioSource(val label: String) {
    SYSTEM("System audio"),
    MIC("Microphone"),
}

data class RingConfig(
    val mode: RingMode = RingMode.SOLID,
    val palette: Int = Palettes.CUSTOM,
    val color: Int = 0xFF33E1FF.toInt(),
    val color2: Int = 0xFFFF2D95.toInt(),
    val brightness: Int = 200,
    val speed: Float = 1f,
    val breatheMs: Int = 2600,
    val smoothing: Float = 0.25f,
    val music: Boolean = false,
    val audioSource: AudioSource = AudioSource.SYSTEM,
    val sensitivity: Float = 1f,
    val autoSensitivity: Boolean = true,
    val notifications: Boolean = true,
    val notifStyle: NotifStyle = NotifStyle.FLASH,
    /** When false every app flashes; when true only enabled apps do. */
    val notifOnlySelected: Boolean = false,
    val notifSeconds: Int = 4,
    val chargingArc: Boolean = true,
    val screenPolicy: ScreenPolicy = ScreenPolicy.ALWAYS,
    val dimWhenScreenOff: Boolean = true,
    val frameRate: FrameRate = FrameRate.BALANCED,
    val autoEco: Boolean = true,
    val startOnBoot: Boolean = true,
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
    /** 0 means "use the global duration". */
    val seconds: Int = 0,
)

/**
 * A temporary effect that sits on top of the base mode. [color] 0 means "run
 * the current ring effect instead of a coloured pattern".
 */
data class Overlay(
    val color: Int,
    val pattern: NotifPattern,
    val durationMs: Long,
    val startedAt: Long,
)

object AppState {
    private var prefs: SharedPreferences? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    val config = MutableStateFlow(RingConfig())
    val running = MutableStateFlow(false)
    val backend = MutableStateFlow("Starting")

    /** True only while this process is actually running the service. */
    val serviceAlive = MutableStateFlow(false)

    private val _battery = MutableStateFlow(BatteryInfo())
    val battery: StateFlow<BatteryInfo> = _battery

    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn

    private val _powerSave = MutableStateFlow(false)
    val powerSave: StateFlow<Boolean> = _powerSave

    private val _audio = MutableStateFlow(AudioState())
    val audio: StateFlow<AudioState> = _audio

    /** Last frame actually sent to the hardware, so the preview can mirror it. */
    val frame = MutableStateFlow(IntArray(8))

    private val _overlay = MutableStateFlow<Overlay?>(null)
    val overlay: StateFlow<Overlay?> = _overlay

    /** Wakes an idle engine loop when something outside the frame changed. */
    val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("aura", Context.MODE_PRIVATE)
        prefs = p

        val storedMode = p.getString("mode_name", null)
            ?.let { name -> runCatching { RingMode.valueOf(name) }.getOrNull() }
            ?: RingMode.legacy.getOrElse(p.getInt("mode", RingMode.SOLID.ordinal)) { RingMode.SOLID }

        config.value = RingConfig(
            mode = storedMode,
            palette = p.getInt("palette", Palettes.CUSTOM),
            color = p.getInt("color", 0xFF33E1FF.toInt()),
            color2 = p.getInt("color2", 0xFFFF2D95.toInt()),
            brightness = p.getInt("brightness", 200),
            speed = p.getFloat("speed", 1f),
            breatheMs = p.getInt("breathe_ms", 2600),
            smoothing = p.getFloat("smoothing", 0.25f),
            music = p.getBoolean("music", false),
            audioSource = enumOf(p.getString("audio_source", null), AudioSource.SYSTEM),
            sensitivity = p.getFloat("sensitivity", 1f),
            autoSensitivity = p.getBoolean("auto_sensitivity", true),
            notifications = p.getBoolean("notifications", true),
            notifStyle = enumOf(p.getString("notif_style", null), NotifStyle.FLASH),
            notifOnlySelected = p.getBoolean("notif_only_selected", false),
            notifSeconds = p.getInt("notif_seconds", 4),
            chargingArc = p.getBoolean("charging_arc", true),
            screenPolicy = enumOf(p.getString("screen_policy", null), ScreenPolicy.ALWAYS),
            dimWhenScreenOff = p.getBoolean("dim_when_off", p.getBoolean("screen_off_dim", true)),
            frameRate = enumOf(p.getString("frame_rate", null), FrameRate.BALANCED),
            autoEco = p.getBoolean("auto_eco", true),
            startOnBoot = p.getBoolean("start_on_boot", p.getBoolean("auto_start", true)),
        )
        running.value = p.getBoolean("running", false)
        _battery.value = BatteryInfo(
            level = p.getInt("battery_level", -1),
            charging = p.getBoolean("battery_charging", false),
        )
    }

    private inline fun <reified T : Enum<T>> enumOf(name: String?, fallback: T): T =
        name?.let { raw -> runCatching { enumValueOf<T>(raw) }.getOrNull() } ?: fallback

    fun update(block: (RingConfig) -> RingConfig) {
        val next = block(config.value)
        if (next == config.value) return
        config.value = next
        wake.tryEmit(Unit)
        scheduleSave()
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = io.launch {
            delay(300)
            persist()
        }
    }

    fun persist() {
        val p = prefs ?: return
        val c = config.value
        p.edit()
            .putString("mode_name", c.mode.name)
            .putInt("palette", c.palette)
            .putInt("color", c.color)
            .putInt("color2", c.color2)
            .putInt("brightness", c.brightness)
            .putFloat("speed", c.speed)
            .putInt("breathe_ms", c.breatheMs)
            .putFloat("smoothing", c.smoothing)
            .putBoolean("music", c.music)
            .putString("audio_source", c.audioSource.name)
            .putFloat("sensitivity", c.sensitivity)
            .putBoolean("auto_sensitivity", c.autoSensitivity)
            .putBoolean("notifications", c.notifications)
            .putString("notif_style", c.notifStyle.name)
            .putBoolean("notif_only_selected", c.notifOnlySelected)
            .putInt("notif_seconds", c.notifSeconds)
            .putBoolean("charging_arc", c.chargingArc)
            .putString("screen_policy", c.screenPolicy.name)
            .putBoolean("dim_when_off", c.dimWhenScreenOff)
            .putString("frame_rate", c.frameRate.name)
            .putBoolean("auto_eco", c.autoEco)
            .putBoolean("start_on_boot", c.startOnBoot)
            .apply()
    }

    fun setRunning(value: Boolean) {
        if (running.value == value) return
        running.value = value
        prefs?.edit()?.putBoolean("running", value)?.apply()
        wake.tryEmit(Unit)
    }

    fun setBattery(info: BatteryInfo) {
        if (_battery.value == info) return
        _battery.value = info
        wake.tryEmit(Unit)
        prefs?.edit()
            ?.putInt("battery_level", info.level)
            ?.putBoolean("battery_charging", info.charging)
            ?.apply()
    }

    fun setScreenOn(value: Boolean) {
        if (_screenOn.value == value) return
        _screenOn.value = value
        wake.tryEmit(Unit)
    }

    fun setPowerSave(value: Boolean) {
        if (_powerSave.value == value) return
        _powerSave.value = value
        wake.tryEmit(Unit)
    }

    fun setAudio(state: AudioState) {
        val previous = _audio.value
        _audio.value = state
        if (previous.hasSignal != state.hasSignal) wake.tryEmit(Unit)
    }

    fun showOverlay(color: Int, pattern: NotifPattern, durationMs: Long) {
        _overlay.value = Overlay(
            color = color,
            pattern = pattern,
            durationMs = durationMs,
            startedAt = SystemClock.elapsedRealtime(),
        )
        wake.tryEmit(Unit)
    }

    fun clearOverlay() {
        if (_overlay.value == null) return
        _overlay.value = null
        wake.tryEmit(Unit)
    }

    fun getString(key: String, fallback: String? = null): String? = prefs?.getString(key, fallback)

    fun putString(key: String, value: String?) {
        prefs?.edit()?.putString(key, value)?.apply()
    }
}

object NotifStore {
    private val _apps = MutableStateFlow<Map<String, NotifApp>>(emptyMap())
    val apps: StateFlow<Map<String, NotifApp>> = _apps
    private var saveJob: Job? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun load(context: Context) {
        val raw = AppState.getString("notif_apps") ?: return
        val map = LinkedHashMap<String, NotifApp>()
        runCatching {
            val arr = JSONArray(raw)
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
                    seconds = o.optInt("seconds", 0).coerceIn(0, 30),
                )
            }
        }
        _apps.value = map
    }

    fun get(pkg: String): NotifApp = _apps.value[pkg] ?: NotifApp(pkg)

    /** Null when the app has never been configured on the Apps tab. */
    fun find(pkg: String): NotifApp? = _apps.value[pkg]

    fun update(pkg: String, block: (NotifApp) -> NotifApp) {
        val next = block(get(pkg)).copy(pkg = pkg)
        if (next == _apps.value[pkg]) return
        _apps.value = _apps.value + (pkg to next)
        scheduleSave()
    }

    private fun scheduleSave() {
        if (saveJob?.isActive == true) return
        saveJob = io.launch {
            delay(350)
            save()
        }
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
                    .put("seconds", app.seconds)
            )
        }
        AppState.putString("notif_apps", arr.toString())
    }
}

/** Colours the user saved from the picker. */
object ColorStore {
    private val _colors = MutableStateFlow<List<Int>>(emptyList())
    val colors: StateFlow<List<Int>> = _colors

    fun load() {
        val raw = AppState.getString("saved_colors") ?: return
        _colors.value = raw.split(",")
            .mapNotNull { runCatching { it.trim().toLong(16).toInt() }.getOrNull() }
            .distinct()
            .take(12)
    }

    fun add(color: Int) {
        val c = color or 0xFF000000.toInt()
        val list = (listOf(c) + _colors.value.filter { it != c }).take(12)
        _colors.value = list
        save()
    }

    fun remove(color: Int) {
        _colors.value = _colors.value.filter { it != color }
        save()
    }

    private fun save() {
        AppState.putString(
            "saved_colors",
            _colors.value.joinToString(",") { Integer.toHexString(it and 0xFFFFFF) },
        )
    }
}
