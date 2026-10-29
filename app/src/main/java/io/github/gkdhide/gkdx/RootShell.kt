package io.github.gkdhide.gkdx

import android.os.SystemClock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * root 命令执行：
 * - 优先常驻 su 会话（一次授权，命令走 stdin/stdout，避免每条命令 fork 一个 su，延迟低 3-5 倍）
 * - 会话不可用时退回一次性 su -c
 */
object RootShell {

    private var proc: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private var seq = 0
    private val lock = Any()

    @Volatile
    var lastError: String? = null
        private set

    private fun ts(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())

    /** 打开常驻会话；成功后 exec 不再 fork su */
    private fun openSession(): Boolean {
        closeSession()
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            proc = p
            writer = BufferedWriter(OutputStreamWriter(p.outputStream), 8192)
            reader = BufferedReader(InputStreamReader(p.inputStream), 32768)
            val out = execLocked("id", 8000)
            val ok = out != null && out.contains("uid=0")
            if (!ok) lastError = "su 会话未返回 uid=0"
            ok
        } catch (t: Throwable) {
            lastError = "无法启动 su：${t.message ?: t.javaClass.simpleName}"
            closeSession()
            false
        }
    }

    private fun closeSession() {
        try { writer?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        try { proc?.destroy() } catch (_: Throwable) {}
        writer = null
        reader = null
        proc = null
    }

    fun isRoot(): Boolean = synchronized(lock) {
        if (proc?.isAlive == true) {
            val out = execLocked("id", 6000)
            if (out != null) return out.contains("uid=0")
        }
        openSession()
    }

    fun exec(cmd: String, timeoutMs: Long = 15000): String = synchronized(lock) {
        var sessionOk = proc?.isAlive == true
        if (!sessionOk) sessionOk = openSession()
        if (sessionOk) {
            val out = execLocked(cmd, timeoutMs)
            if (out != null) return out
            closeSession()
        }
        return oneShot(cmd, timeoutMs)
    }

    /** 会话内执行；超时或会话损坏返回 null */
    private fun execLocked(cmd: String, timeoutMs: Long): String? {
        val w = writer ?: return null
        val r = reader ?: return null
        val id = ++seq
        val marker = "__GKDX_END_${id}__"
        return try {
            w.write("$cmd; echo $marker\n")
            w.flush()
            val sb = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var finished = false
            while (SystemClock.elapsedRealtime() < deadline) {
                if (r.ready()) {
                    val line = r.readLine() ?: break
                    if (line.trim() == marker) { finished = true; break }
                    sb.append(line).append('\n')
                } else {
                    Thread.sleep(8)
                }
            }
            if (!finished) {
                lastError = "命令超时(${timeoutMs}ms)：${cmd.take(40)}"
                null
            } else sb.toString()
        } catch (t: Throwable) {
            lastError = "会话异常：${t.message ?: t.javaClass.simpleName}"
            null
        }
    }

    private fun oneShot(cmd: String, timeoutMs: Long): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val sb = StringBuilder()
        val r = BufferedReader(InputStreamReader(p.inputStream), 16384)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (r.ready()) {
                val line = r.readLine() ?: break
                sb.append(line).append('\n')
            } else {
                if (!p.isAlive && !r.ready()) break
                Thread.sleep(8)
            }
        }
        try { p.destroy() } catch (_: Throwable) {}
        sb.toString()
    } catch (t: Throwable) {
        lastError = "su -c 失败：${t.message ?: t.javaClass.simpleName}"
        ""
    }
}
