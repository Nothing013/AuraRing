package app.aura.ring

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class AuraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppState.init(this)
        NotifStore.load(this)
        ColorStore.load()

        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            "aura_service",
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
}
