package app.aura.ring.service

import android.app.Notification
import android.content.ComponentName
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.aura.ring.AppState
import app.aura.ring.NotifStore
import app.aura.ring.NotifStyle

/**
 * Turns notification posts into ring events.
 *
 * It never starts the ring on its own: notifications decorate a running ring.
 * Ongoing and media notifications are ignored because they fire constantly
 * while a song plays and would otherwise fight the audio-reactive effect.
 */
class AuraNotificationListener : NotificationListenerService() {

    private val lastPost = HashMap<String, Long>()
    private var lastAny = 0L
    private val handler = Handler(Looper.getMainLooper())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (sbn.isOngoing) return

        val config = AppState.config.value
        if (!config.notifications || config.notifStyle == NotifStyle.OFF) return
        if (!AppState.serviceAlive.value || !AppState.running.value) return

        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return
        if (isMedia(notification)) return

        val app = NotifStore.get(pkg)
        if (!app.enabled) return

        val now = SystemClock.elapsedRealtime()
        if (now - (lastPost[pkg] ?: 0L) < PER_APP_GAP_MS) return
        if (now - lastAny < GLOBAL_GAP_MS) return
        lastPost[pkg] = now
        lastAny = now

        val seconds = (if (app.seconds > 0) app.seconds else config.notifSeconds)
            .coerceIn(1, 30)
        val color = if (config.notifStyle == NotifStyle.EFFECT) 0 else app.color
        AppState.showOverlay(color, app.pattern, seconds * 1000L)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerConnected() {
        AppState.init(this)
        NotifStore.load(this)
        lastPost.clear()
    }

    override fun onListenerDisconnected() {
        // Some ROMs drop the binding after a memory trim. Ask once, gently.
        handler.postDelayed({
            runCatching {
                requestRebind(ComponentName(this, AuraNotificationListener::class.java))
            }
        }, 4_000L)
    }

    private fun isMedia(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_TRANSPORT) return true
        val extras = notification.extras ?: return false
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
    }

    companion object {
        private const val PER_APP_GAP_MS = 1_600L
        private const val GLOBAL_GAP_MS = 400L
    }
}
