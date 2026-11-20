package app.aura.ring.core

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Hand-written client for `miui.lights.ILightsManager`. Keeping our own
 * interface avoids a class-name collision with the framework's hidden AIDL.
 *
 * Interface order: 1=setColorfulLight, 2=setColorCommon, 3=setColorLed,
 * 4=setCustomLight.
 */
object BinderRing {
    private const val DESCRIPTOR = "miui.lights.ILightsManager"
    private const val TX_COLOR_COMMON = 2
    private const val TX_CUSTOM_LIGHT = 4
    private const val PKG = "com.android.camera"
    private const val STYLE_MUSIC = 3
    private const val STYLE_CUSTOM = 12

    @Volatile
    private var binder: IBinder? = null

    fun running(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun permitted(): Boolean = try {
        running() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun obtain(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        val raw = SystemServiceHelper.getSystemService(DESCRIPTOR) ?: return null
        return ShizukuBinderWrapper(raw).also { binder = it }
    }

    /** Exact, timer-free global colour. 0 turns the ring off. */
    fun setColor(color: Int): Boolean {
        val b = obtain() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(color)
            data.writeString(PKG)
            data.writeInt(STYLE_MUSIC)
            data.writeInt(0)
            b.transact(TX_COLOR_COMMON, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            binder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Colour plus a silicon-timed fade pulse. The service turns the ring off
     * after onMs+offMs, so this is for notifications rather than animation.
     */
    fun pulse(color: Int, onMs: Int, offMs: Int): Boolean {
        val b = obtain() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeInt(color)
            data.writeInt(0)          // flashMode
            data.writeInt(onMs)
            data.writeInt(offMs)
            data.writeInt(0)          // brightNessMode
            data.writeString(PKG)
            data.writeInt(STYLE_CUSTOM)
            data.writeInt(0)
            b.transact(TX_CUSTOM_LIGHT, data, reply, 0)
            reply.readException()
            true
        } catch (_: Throwable) {
            binder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun reset() {
        binder = null
    }

    /** Run a shell command through Shizuku (used for Settings.Global writes). */
    fun shell(command: Array<String>): Boolean {
        if (!permitted()) return false
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java,
            )
            method.isAccessible = true
            val process = method.invoke(null, command, null, null) as Process
            process.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }
}
