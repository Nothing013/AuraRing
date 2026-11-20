package app.aura.ring.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.BatteryInfo
import app.aura.ring.MainActivity
import app.aura.ring.NotifPattern
import app.aura.ring.Overlay
import app.aura.ring.R
import app.aura.ring.audio.AudioEngine
import app.aura.ring.core.RingController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class RingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var controller: RingController
    private lateinit var engine: RingEngine
    private lateinit var audio: AudioEngine

    private var batteryReceiver: BroadcastReceiver? = null
    private var screenReceiver: BroadcastReceiver? = null

    private var cameraManager: CameraManager? = null
    private val unavailableCameras = mutableSetOf<String>()
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            synchronized(unavailableCameras) {
                unavailableCameras.remove(cameraId)
                AppState.cameraInUse.value = unavailableCameras.isNotEmpty()
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            synchronized(unavailableCameras) {
                unavailableCameras.add(cameraId)
                AppState.cameraInUse.value = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        createChannel()
        startForegroundCompat(buildNotification())

        controller = RingController(this)
        engine = RingEngine(controller)
        audio = AudioEngine(this)
        AppState.screenOn.value = getSystemService(PowerManager::class.java).isInteractive
        registerReceivers()
        registerCameraObserver()

        scope.launch {
            val backend = controller.init()
            AppState.backend.value = backend
            if (AppState.config.value.music) audio.start()
            engine.start()
            AppState.setRunning(true)
        }

        scope.launch {
            AppState.config
                .map { it.music }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) audio.start() else audio.stop()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_FLASH -> {
                val color = intent.getIntExtra(EXTRA_COLOR, 0xFF33E1FF.toInt())
                val pattern = runCatching {
                    NotifPattern.valueOf(
                        intent.getStringExtra(EXTRA_PATTERN) ?: NotifPattern.PULSE.name
                    )
                }.getOrDefault(NotifPattern.PULSE)
                val duration = intent.getIntExtra(EXTRA_DURATION, 4).coerceIn(1, 30) * 1000L
                AppState.overlay.value = Overlay(color, pattern, duration, SystemClock.elapsedRealtime())
            }
            ACTION_REAPPLY -> {
                AppState.preview.value = AppState.preview.value.copyOf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.stop()
        audio.stop()
        unregisterReceiverSafe(batteryReceiver)
        unregisterReceiverSafe(screenReceiver)
        runCatching {
            cameraManager?.unregisterAvailabilityCallback(cameraCallback)
        }
        cameraManager = null
        AppState.cameraInUse.value = false
        AppState.setRunning(false)
        AppState.overlay.value = null
        scope.cancel()
        // Restore the system strip on a background thread; the process may
        // outlive the service briefly and this releases the hardware cleanly.
        Thread {
            kotlinx.coroutines.runBlocking {
                runCatching { controller.restore() }
            }
        }.start()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun registerReceivers() {
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
                val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
                AppState.battery.value = BatteryInfo(
                    level = percent,
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL,
                    plugged = plugged != 0,
                )
            }
        }
        ContextCompat.registerReceiver(
            this, batteryReceiver, batteryFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AppState.screenOn.value = intent?.action == Intent.ACTION_SCREEN_ON
            }
        }
        ContextCompat.registerReceiver(
            this, screenReceiver, screenFilter, ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun unregisterReceiverSafe(receiver: BroadcastReceiver?) {
        if (receiver == null) return
        runCatching { unregisterReceiver(receiver) }
    }

    private fun registerCameraObserver() {
        cameraManager = runCatching {
            getSystemService(CameraManager::class.java)
        }.getOrNull() ?: return
        runCatching {
            cameraManager?.registerAvailabilityCallback(
                cameraCallback,
                Handler(Looper.getMainLooper()),
            )
        }
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_running))
            .setContentText("${config.mode.label} · ${AppState.backend.value}")
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
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

    companion object {
        const val ACTION_STOP = "app.aura.ring.STOP"
        const val ACTION_FLASH = "app.aura.ring.FLASH"
        const val ACTION_REAPPLY = "app.aura.ring.REAPPLY"
        const val EXTRA_COLOR = "color"
        const val EXTRA_PATTERN = "pattern"
        const val EXTRA_DURATION = "duration"

        private const val CHANNEL_ID = "aura_service"
        private const val NOTIFICATION_ID = 47
    }
}
