package app.aura.ring.service

import android.app.Notification
import android.content.Intent
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifStore

/**
 * Turns notification posts into short ring overlays. Only packages the user
 * enabled on the Apps tab are considered; ongoing and group-summary
 * notifications are ignored, and each app is rate-limited so a noisy chat
 * cannot turn the ring into a strobe.
 */
class AuraNotificationListener : NotificationListenerService() {

    private val lastPost = HashMap<String, Long>()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val config = AppState.config.value
        if (!config.notifications || !AppState.running.value) return

        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return

        val app = NotifStore.get(pkg)
        if (!app.enabled) return

        val now = SystemClock.elapsedRealtime()
        val previous = lastPost[pkg] ?: 0L
        if (now - previous < MIN_GAP_MS) return
        lastPost[pkg] = now

        val intent = Intent(this, RingService::class.java)
            .setAction(RingService.ACTION_FLASH)
            .putExtra(RingService.EXTRA_COLOR, app.color)
            .putExtra(RingService.EXTRA_PATTERN, app.pattern.name)
            .putExtra(RingService.EXTRA_DURATION, 4)
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onListenerConnected() {
        AppState.init(this)
        NotifStore.load(this)
    }

    companion object {
        private const val MIN_GAP_MS = 1_800L
    }
}
