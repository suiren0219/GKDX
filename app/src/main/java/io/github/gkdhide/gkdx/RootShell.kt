package io.github.gkdhide.gkdx

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * root 命令执行 v2：每条命令都是独立进程（su -c），一次性读完整输出。
 *
 * 不再使用常驻会话 + 全局锁：HyperOS/Android 37 上 uiautomator 的输出可能
 * 不带换行，常驻会话逐行等 marker 会挂死，并因全局锁冻结整条引擎
 * （焦点轮询、logcat、点击全部停摆）。独立进程自带超时兜底，任何单条
 * 命令挂死都不会影响其他线程。
 */
object RootShell {

    @Volatile
    var lastError: String? = null
        private set

    fun isRoot(): Boolean {
        val out = exec("id", 6000)
        val ok = out.contains("uid=0")
        if (!ok) lastError = "su 未返回 uid=0（输出：${out.take(80)}）"
        return ok
    }

    /** 同步执行一条命令，带硬超时；超时后强制销毁进程并返回已读到的部分 */
    fun exec(cmd: String, timeoutMs: Long = 15000): String = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val r = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8), 65536)
        val sb = StringBuilder()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // 单字符读取：不依赖换行，dump 输出不带 \n 也能完整收下
        val buf = CharArray(8192)
        var timedOut = false
        while (true) {
            if (System.nanoTime() >= deadline) {
                timedOut = true
                break
            }
            if (r.ready()) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
                if (sb.length > 4_000_000) break
            } else {
                if (!p.isAlive) {
                    // 进程已退出，把剩余缓冲一次性读完再结束
                    while (r.ready()) {
                        val n = r.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    break
                }
                Thread.sleep(5)
            }
        }
        if (timedOut) lastError = "命令超时(${timeoutMs}ms)：${cmd.take(48)}"
        try { r.close() } catch (_: Throwable) {}
        try { p.destroy() } catch (_: Throwable) {}
        try { p.waitFor(300, TimeUnit.MILLISECONDS) } catch (_: Throwable) {}
        sb.toString()
    } catch (t: Throwable) {
        lastError = "su -c 失败：${t.message ?: t.javaClass.simpleName}"
        ""
    }
}
