package app.aura.ring.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter

data class ShellResult(val code: Int, val output: String)

/**
 * A single long-lived `su` process. Spawning su per frame is far too slow, so
 * every effect frame is piped through this shell instead.
 */
class RootShell private constructor(private val process: Process) {
    private val mutex = Mutex()
    private val writer: BufferedWriter = process.outputStream.bufferedWriter()
    private val reader: BufferedReader = process.inputStream.bufferedReader()
    private val marker = "__AURA_DONE__"

    suspend fun exec(command: String): ShellResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                writer.write(command)
                writer.write("\n")
                writer.write("echo $marker$?\n")
                writer.flush()

                val out = StringBuilder()
                var code = -1
                while (true) {
                    val line = reader.readLine() ?: return@withLock ShellResult(-1, out.toString())
                    if (line.startsWith(marker)) {
                        code = line.removePrefix(marker).trim().toIntOrNull() ?: -1
                        break
                    }
                    out.appendLine(line)
                }
                ShellResult(code, out.toString())
            } catch (t: Throwable) {
                ShellResult(-1, t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun close() {
        runCatching {
            writer.write("exit\n")
            writer.flush()
        }
        runCatching { process.destroy() }
    }

    companion object {
        suspend fun start(): RootShell? = withContext(Dispatchers.IO) {
            try {
                val process = ProcessBuilder("su").redirectErrorStream(true).start()
                val shell = RootShell(process)
                val result = shell.exec("id")
                if (result.code == 0 && result.output.contains("uid=0")) {
                    shell
                } else {
                    shell.close()
                    null
                }
            } catch (_: Throwable) {
                null
            }
        }
    }
}
