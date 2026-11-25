package app.aura.ring.core

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/** Small read-only helpers for the HyperOS lighting settings. */
object HyperOs {
    private const val STRIP_KEY = "settings_strip_light_enable"

    fun stripValue(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, STRIP_KEY, -1)
    }.getOrDefault(-1)

    fun stripEnabled(context: Context): Boolean = stripValue(context) != 0

    /**
     * Apps the system's own "rhythmic light" is currently enabled for. The
     * ring is a shared resource; when the stock effect is on it can fight with
     * Aura while in Shizuku mode.
     */
    fun rhythmApps(context: Context): List<String> {
        return runCatching {
            val am = context.getSystemService(AudioManager::class.java) ?: return emptyList()
            val raw = am.getParameters("Get_MusicLight_Package_State=") ?: return emptyList()
            raw.split(';')
                .mapNotNull { entry ->
                    val parts = entry.split('=', limit = 2)
                    if (parts.size == 2 && parts[1].trim().equals("true", true)) parts[0].trim() else null
                }
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
