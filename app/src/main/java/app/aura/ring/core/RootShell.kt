package app.aura.ring.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.atomic.AtomicBoolean

data class ShellResult(val code: Int, val output: String = "")

/**
 * One long-lived `su` process. Starting a shell per frame is far too slow, so
 * every effect frame is piped through this single process.
 *
 * The shell is treated as disposable: if it dies or a command takes too long,
 * it is marked dead so [RingController] can start a fresh one and re-init the
 * chip instead of freezing on the last frame.
 */
class RootShell private constructor(private val process: Process) {
    private val mutex = Mutex()
    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val reader: BufferedReader = process.inputStream.bufferedReader()

    @Volatile
    var dead: Boolean = false
        private set

    val alive: Boolean get() = !dead && process.isAlive

    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!alive) return@withContext ShellResult(-1)
        mutex.withLock {
            try {
                writer.write(command)
                writer.write("\n")
                writer.write("echo $MARKER\$?\n")
                writer.flush()

                val out = StringBuilder()
                while (true) {
                    val line = reader.readLine() ?: run {
                        dead = true
                        return@withLock ShellResult(-1, out.toString())
                    }
                    if (line.startsWith(MARKER)) {
                        val code = line.removePrefix(MARKER).trim().toIntOrNull() ?: -1
                        return@withLock ShellResult(code, out.toString())
                    }
                    out.appendLine(line)
                }
                @Suppress("UNREACHABLE_CODE")
                ShellResult(-1, out.toString())
            } catch (t: Throwable) {
                dead = true
                ShellResult(-1, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun close() {
        dead = true
        runCatching {
            writer.write("exit\n")
            writer.flush()
        }
        runCatching { process.destroy() }
    }

    companion object {
        private const val MARKER = "__AURA_DONE__"
        private const val START_TIMEOUT_MS = 12_000L

        suspend fun start(): RootShell? = withContext(Dispatchers.IO) {
            val process = try {
                ProcessBuilder("su").redirectErrorStream(true).start()
            } catch (_: Throwable) {
                return@withContext null
            }
            val shell = RootShell(process)
            // The su manager may show a prompt; wait for it, but never forever.
            val finished = AtomicBoolean(false)
            val killer = Thread {
                runCatching { Thread.sleep(START_TIMEOUT_MS) }
                if (!finished.get()) shell.close()
            }.apply {
                isDaemon = true
                start()
            }
            val probe = shell.exec("id")
            finished.set(true)
            killer.interrupt()
            if (probe.code == 0 && probe.output.contains("uid=0")) {
                shell
            } else {
                shell.close()
                null
            }
        }
    }
}
