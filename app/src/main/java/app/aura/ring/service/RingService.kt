package app.aura.ring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.AudioSource
import app.aura.ring.BatteryInfo
import app.aura.ring.ColorStore
import app.aura.ring.MainActivity
import app.aura.ring.NotifPattern
import app.aura.ring.NotifStore
import app.aura.ring.R
import app.aura.ring.audio.AudioEngine
import app.aura.ring.core.RingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: RingController
    private lateinit var engine: RingEngine
    private lateinit var audio: AudioEngine

    private var batteryReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var powerReceiver: BroadcastReceiver? = null
    private var micType = false

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        AppState.serviceAlive.value = true
        NotifStore.load(this)
        ColorStore.load()
        createChannel()
        startForegroundCompat(buildNotification())

        controller = RingController(this)
        engine = RingEngine(controller)
        audio = AudioEngine(this)
        val power = getSystemService(PowerManager::class.java)
        AppState.setScreenOn(power.isInteractive)
        AppState.setPowerSave(power.isPowerSaveMode)
        registerReceivers()

        scope.launch {
            val backend = controller.init()
            AppState.backend.value = backend
            AppState.setRunning(true)
            engine.start()
            audio.configure(
                AppState.config.value.audioSource,
                AppState.config.value.sensitivity,
                AppState.config.value.autoSensitivity,
            )
            audio.setRunning(AppState.config.value.music)
            promoteMicrophoneIfAllowed()
        }

        scope.launch {
            AppState.config.collect { config ->
                audio.configure(config.audioSource, config.sensitivity, config.autoSensitivity)
                audio.setRunning(config.music)
                promoteMicrophoneIfAllowed()
            }
        }

        scope.launch {
            combine(
                AppState.config,
                AppState.backend,
            ) { config, backend -> config.mode.label to backend }
                .distinctUntilChanged()
                .collect { refreshNotification() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                AppState.setRunning(false)
                stopSelf()
            }

            ACTION_FLASH -> {
                val color = intent.getIntExtra(EXTRA_COLOR, 0xFF33E1FF.toInt())
                val pattern = runCatching {
                    NotifPattern.valueOf(
                        intent.getStringExtra(EXTRA_PATTERN) ?: NotifPattern.PULSE.name
                    )
                }.getOrDefault(NotifPattern.PULSE)
                val seconds = intent.getIntExtra(EXTRA_DURATION, 4).coerceIn(1, 30)
                AppState.showOverlay(color, pattern, seconds * 1000L)
            }

            ACTION_RECONNECT -> scope.launch {
                controller.reconnect("user request")
                AppState.backend.value = controller.backendName
            }

            ACTION_REAPPLY -> {
                controller.invalidate()
                AppState.wake.tryEmit(Unit)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.stop()
        audio.shutdown()
        unregisterReceiverSafe(batteryReceiver)
        unregisterReceiverSafe(screenReceiver)
        unregisterReceiverSafe(powerReceiver)
        AppState.setRunning(false)
        AppState.serviceAlive.value = false
        AppState.clearOverlay()
        AppState.backend.value = "Off"
        scope.cancel()
        // Hand the chip back even if the process dies a moment later.
        Thread {
            runCatching { runBlocking { controller.restore() } }
        }.apply { isDaemon = true }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun registerReceivers() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                AppState.setBattery(
                    BatteryInfo(
                        level = percent,
                        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL,
                        plugged = plugged != 0,
                    )
                )
            }
        }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AppState.setScreenOn(intent?.action == Intent.ACTION_SCREEN_ON)
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AppState.setPowerSave(
                    getSystemService(PowerManager::class.java).isPowerSaveMode
                )
            }
        }
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver?) {
        if (receiver == null) return
        runCatching { unregisterReceiver(receiver) }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val config = AppState.config.value
        val detail = buildString {
            append(config.mode.label)
            append(" · ")
            append(AppState.backend.value)
            if (config.music && AppState.audio.value.hasSignal) {
                append(" · audio")
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_running))
            .setContentText(detail)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Microphone capture inside a background service needs the microphone
     * foreground-service type. It is only added once RECORD_AUDIO is actually
     * granted, because starting with that type without the permission throws.
     */
    private fun promoteMicrophoneIfAllowed() {
        if (micType || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val config = AppState.config.value
        if (!config.music || config.audioSource != AudioSource.MIC) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        micType = true
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        }.onFailure { micType = false }
    }

    companion object {
        const val ACTION_STOP = "app.aura.ring.STOP"
        const val ACTION_FLASH = "app.aura.ring.FLASH"
        const val ACTION_REAPPLY = "app.aura.ring.REAPPLY"
        const val ACTION_RECONNECT = "app.aura.ring.RECONNECT"
        const val EXTRA_COLOR = "color"
        const val EXTRA_PATTERN = "pattern"
        const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "aura_service"
        private const val NOTIFICATION_ID = 47
    }
}
