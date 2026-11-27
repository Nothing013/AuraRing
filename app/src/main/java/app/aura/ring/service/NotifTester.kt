package app.aura.ring.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifPattern
import app.aura.ring.R

/**
 * Small helpers for the notification path: posting a real test notification
 * (so the whole listener → overlay → engine flow can be checked) and asking the
 * system to bind the listener again after HyperOS drops it.
 */
object NotifTester {
    /** The listener ignores its own package except for this tag. */
    const val TEST_TAG = "aura_test"
    const val CHANNEL_ID = "aura_test"

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_test),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_test_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun accessGranted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context)
            .contains(context.packageName)

    /**
     * Posts a normal, non-ongoing notification from Aura itself. The listener
     * accepts this one tag so the user can confirm notifications work without
     * waiting for a real message.
     */
    fun postTest(context: Context): Boolean {
        if (!accessGranted(context)) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_test_title))
            .setContentText(context.getString(R.string.notif_test_text))
            .setAutoCancel(true)
            .setSilent(true)
            .build()
        val id = (SystemClock.elapsedRealtime() / 1000L).toInt()
        runCatching {
            NotificationManagerCompat.from(context).notify(TEST_TAG, id, notification)
        }.onFailure { return false }
        return true
    }

    /**
     * HyperOS sometimes leaves notification access enabled without binding the
     * listener, especially right after the permission is granted or after the
     * app was force-stopped. Asking for a rebind is harmless when it is
     * already connected.
     */
    fun requestRebind(context: Context): Boolean {
        if (!accessGranted(context)) return false
        return runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, AuraNotificationListener::class.java)
            )
            true
        }.getOrDefault(false)
    }

    /**
     * Runs the notification overlay directly, for the "Test flash" button.
     * Returns false when the ring is not running.
     */
    fun flash(): Boolean {
        if (!AppState.running.value) return false
        AppState.showOverlay(
            AppState.config.value.color,
            NotifPattern.PULSE,
            3_000L,
        )
        return true
    }
}
