package io.github.gkdhide.gkdx

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/** 以 root 执行 shell 命令的最小封装（su -c），用于 Root 模式，不注入任何应用进程 */
object RootShell {

    fun exec(cmd: String, timeoutMs: Long = 20000): String {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val buf = CharArray(8192)
                while (true) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > 4_000_000) break
                }
            }
            process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            sb.toString()
        } catch (t: Throwable) {
            ""
        } finally {
            try {
                process?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    fun isRoot(): Boolean = exec("id").contains("uid=0")
}
