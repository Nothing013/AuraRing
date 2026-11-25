package app.aura.ring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.aura.ring.AppState
import app.aura.ring.NotifStore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        AppState.init(context)
        NotifStore.load(context)
        if (!AppState.config.value.startOnBoot || !AppState.running.value) return

        runCatching {
            ContextCompat.startForegroundService(
                context, Intent(context, RingService::class.java)
            )
        }
    }
}
