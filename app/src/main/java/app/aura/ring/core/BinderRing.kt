package app.aura.ring.core

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Hand-written client for `miui.lights.ILightsManager`, kept independent of the
 * framework's hidden AIDL.
 *
 * Interface order: 1 = setColorfulLight, 2 = setColorCommon, 3 = setColorLed,
 * 4 = setCustomLight.
 *
 * `setColorCommon` with style 3 (the music-rhythm light) is used for animation
 * because it has no server-side auto-off timer, so it never flickers between
 * frames. This is the only path a non-root Shizuku shell can drive.
 */
object BinderRing {
    private const val TAG = "AuraBinder"
    private const val DESCRIPTOR = "miui.lights.ILightsManager"

    private const val TX_COLORFUL = 1
    private const val TX_COLOR_COMMON = 2
    private const val TX_CUSTOM_LIGHT = 4

    private const val PKG = "com.android.camera"
    private const val STYLE_MUSIC = 3
    private const val STYLE_RESET = -1

    @Volatile
    private var binder: IBinder? = null

    fun serviceRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) {
        false
    }

    fun permitted(): Boolean = try {
        serviceRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun obtain(): IBinder? {
        binder?.let { if (it.isBinderAlive) return it }
        val raw = try {
            SystemServiceHelper.getSystemService(DESCRIPTOR)
        } catch (t: Throwable) {
            Log.w(TAG, "getSystemService failed", t)
            null
        } ?: return null
        val wrapper = try {
            ShizukuBinderWrapper(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "wrapper failed", t)
            return null
        }
        binder = wrapper
        return wrapper
    }

    private inline fun transact(code: Int, fill: (Parcel) -> Unit): Boolean {
        val b = obtain() ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            fill(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "transaction $code failed", t)
            binder = null
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Immediate, timer-free colour. 0 turns the ring off. */
    fun setColor(color: Int): Boolean = transact(TX_COLOR_COMMON) { data ->
        data.writeInt(color)
        data.writeString(PKG)
        data.writeInt(STYLE_MUSIC)
        data.writeInt(0)
    }

    /** Colour plus a silicon-timed flash; used when a fade is nicer than a loop. */
    fun pulse(color: Int, onMs: Int, offMs: Int): Boolean = transact(TX_CUSTOM_LIGHT) { data ->
        data.writeInt(color)
        data.writeInt(0)
        data.writeInt(onMs)
        data.writeInt(offMs)
        data.writeInt(0)
        data.writeString(PKG)
        data.writeInt(12)
        data.writeInt(0)
    }

    /** Hands the ring back to the framework so other scenes can take over. */
    fun resetScene(): Boolean {
        var ok = transact(TX_COLOR_COMMON) { data ->
            data.writeInt(0)
            data.writeString(PKG)
            data.writeInt(STYLE_RESET)
            data.writeInt(0)
        }
        ok = transact(TX_CUSTOM_LIGHT) { data ->
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeInt(0)
            data.writeString(PKG)
            data.writeInt(STYLE_RESET)
            data.writeInt(0)
        } || ok
        ok = transact(TX_COLORFUL) { data ->
            data.writeString(PKG)
            data.writeInt(STYLE_RESET)
            data.writeInt(0)
        } || ok
        return ok
    }

    fun invalidate() {
        binder = null
    }

    /** Run a shell command through Shizuku (Settings.Global writes). */
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
        } catch (t: Throwable) {
            Log.w(TAG, "shell failed: ${command.joinToString(" ")}", t)
            false
        }
    }
}
