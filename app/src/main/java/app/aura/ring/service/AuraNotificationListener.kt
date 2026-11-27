package app.aura.ring.service

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifPattern
import app.aura.ring.NotifStore
import app.aura.ring.NotifStyle

/**
 * Turns notification posts into ring events.
 *
 * Like DashDazz, a notification from any app lights the ring by default; the
 * Apps tab adds per-app overrides. "Only selected apps" in that tab switches
 * to a whitelist.
 *
 * It never switches a stopped ring on. If the ring was on and HyperOS killed
 * the foreground service, the listener tries to revive it so notifications
 * keep working. Ongoing, no-clear and group-summary notifications are ignored,
 * and so are media notifications, because they update constantly while music
 * plays and would otherwise fight the audio-reactive effect.
 */
class AuraNotificationListener : NotificationListenerService() {

    private val lastPost = HashMap<String, Long>()
    private var lastAny = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        // Ignore Aura's own foreground-service notice, but accept the test
        // notification posted by the button on the Settings tab.
        if (pkg == packageName && sbn.tag != NotifTester.TEST_TAG) return

        val config = AppState.config.value
        if (!config.notifications || config.notifStyle == NotifStyle.OFF) return

        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_NO_CLEAR != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (isMedia(notification)) return

        // DashDazz behaviour: every app is allowed unless the user turned on
        // the whitelist. Per-app colours and patterns are overrides.
        val entry = NotifStore.find(pkg)
        val allowed = if (config.notifOnlySelected) {
            entry?.enabled == true
        } else {
            // Unconfigured apps flash the primary colour; an entry the user
            // explicitly switched off stays quiet.
            entry?.enabled != false
        }
        if (!allowed) return

        if (!AppState.running.value) {
            Log.d(TAG, "ignored $pkg: ring is off")
            return
        }
        if (!AppState.serviceAlive.value && !restartService()) {
            Log.d(TAG, "ignored $pkg: service is not running")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (now - (lastPost[pkg] ?: 0L) < PER_APP_GAP_MS) return
        if (now - lastAny < GLOBAL_GAP_MS) return
        lastPost[pkg] = now
        lastAny = now

        val seconds = (entry?.seconds?.takeIf { it > 0 } ?: config.notifSeconds)
            .coerceIn(1, 30)
        if (config.notifStyle == NotifStyle.EFFECT) {
            AppState.showOverlay(0, NotifPattern.PULSE, seconds * 1000L)
            Log.d(TAG, "effect for $pkg ${seconds}s")
        } else {
            val color = entry?.color ?: config.color
            val pattern = entry?.pattern ?: NotifPattern.PULSE
            AppState.showOverlay(color, pattern, seconds * 1000L)
            Log.d(TAG, "flash $pkg ${pattern.name} ${seconds}s")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerConnected() {
        AppState.init(this)
        NotifStore.load(this)
        lastPost.clear()
        Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        // Some ROMs drop the binding after a memory trim. Ask once, gently.
        handler.postDelayed({
            runCatching {
                requestRebind(ComponentName(this, AuraNotificationListener::class.java))
            }
        }, 4_000L)
    }

    /**
     * HyperOS sometimes kills the foreground service while leaving the
     * notification listener bound. If the user had the ring switched on, try
     * to bring it back so notifications keep working. Background service
     * starts may be blocked; that failure is expected and harmless.
     */
    private fun restartService(): Boolean = runCatching {
        ContextCompat.startForegroundService(
            this, Intent(this, RingService::class.java)
        )
        true
    }.getOrDefault(false)

    private fun isMedia(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    companion object {
        private const val TAG = "AuraRingNotif"
        private const val PER_APP_GAP_MS = 1_600L
        private const val GLOBAL_GAP_MS = 400L
    }
}
