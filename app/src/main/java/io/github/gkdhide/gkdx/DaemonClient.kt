package io.github.gkdhide.gkdx

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 常驻无障碍守护进程的客户端（127.0.0.1:28733）。
 *
 * 守护进程以 shell/root 身份经 app_process 运行，持有 UiAutomation 连接：
 * DUMP 一次 binder 调用即取当前窗口树（~10-80ms），无 JVM 重启、无 idle 等待。
 * 请求-应答协议，无事件推送，读写天然不交织。
 */
object DaemonClient {

    private const val PORT = 28733
    private const val DEX_ASSET = "gkdx_daemon.dex"
    private const val DEX_REMOTE = "/data/local/tmp/gkdx_daemon.dex"

    @Volatile var available: Boolean = false
        private set

    private var socket: Socket? = null
    private var out: BufferedOutputStream? = null
    private var din: DataInputStream? = null
    private val ioLock = Any()

    fun ensureStarted(ctx: Context?): Boolean {
        val c = ctx ?: return false
        return try {
            // 1. dex 从 assets 落到私有目录，再由 root 拷到 /data/local/tmp
            val bytes = c.assets.open(DEX_ASSET).use { it.readBytes() }
            val local = File(c.filesDir, DEX_ASSET)
            if (!local.exists() || local.length() != bytes.size.toLong()) {
                local.writeBytes(bytes)
            }
            RootShell.exec(
                "mkdir -p /data/local/tmp; " +
                        "if [ ! -f $DEX_REMOTE ] || [ \"\$(stat -c %s $DEX_REMOTE 2>/dev/null)\" != \"${bytes.size}\" ]; then " +
                        "cp '${local.absolutePath}' $DEX_REMOTE && chmod 644 $DEX_REMOTE; fi", 8000
            )
            // 2. 杀旧实例并拉起（nohup + & 使 su 退出后守护进程存活）
            RootShell.exec(
                "pkill -f '[g]kdx.daemon.Main' 2>/dev/null; sleep 0.5; " +
                        "nohup CLASSPATH=$DEX_REMOTE:/system/framework/uiautomator.jar " +
                        "app_process /system/bin gkdx.daemon.Main >/dev/null 2>&1 &", 6000
            )
            // 3. 连接（守护进程初始化需要一点时间）
            var ok = false
            val deadline = System.currentTimeMillis() + 4000
            while (System.currentTimeMillis() < deadline) {
                if (connect()) { ok = true; break }
                Thread.sleep(250)
            }
            available = ok
            ok
        } catch (t: Throwable) {
            SafeLog.log(t)
            available = false
            false
        }
    }

    private fun connect(): Boolean = try {
        closeQuiet()
        val s = Socket()
        s.connect(InetSocketAddress("127.0.0.1", PORT), 500)
        s.setTcpNoDelay(true)
        s.soTimeout = 1200
        socket = s
        val output = BufferedOutputStream(s.getOutputStream(), 1 shl 14)
        val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 14))
        out = output
        din = input
        writeLine("PING")
        input.readLine() == "PONG"
    } catch (t: Throwable) {
        closeQuiet()
        false
    }

    /** 读当前窗口树并解析；失败返回 null（调用方可降级快层） */
    fun dump(timeoutMs: Int = 2000): XmlNode? {
        synchronized(ioLock) {
            val s = socket ?: return null
            val input = din ?: return null
            return try {
                s.soTimeout = timeoutMs
                writeLine("DUMP")
                val head = input.readLine() ?: run { markDead(); return null }
                when {
                    head == "EMPTY" -> null
                    head.startsWith("XML ") -> {
                        val n = head.substring(4).trim().toIntOrNull() ?: run { markDead(); return null }
                        val bytes = ByteArray(n)
                        input.readFully(bytes)
                        val xml = String(bytes, Charsets.UTF_8)
                        XmlNodeAdapter.parse(xml)
                    }
                    else -> { markDead(); null }
                }
            } catch (t: Throwable) {
                markDead()
                null
            }
        }
    }

    fun alive(): Boolean = try {
        synchronized(ioLock) {
            val s = socket ?: return false
            val input = din ?: return false
            s.soTimeout = 900
            writeLine("PING")
            input.readLine() == "PONG"
        }
    } catch (t: Throwable) {
        false
    }

    fun kill() {
        available = false
        closeQuiet()
        RootShell.exec("pkill -f '[g]kdx.daemon.Main' 2>/dev/null", 3000)
    }

    private fun writeLine(s: String) {
        out!!.write((s + "\n").toByteArray(Charsets.UTF_8))
        out!!.flush()
    }

    private fun markDead() {
        available = false
        closeQuiet()
    }

    private fun closeQuiet() {
        try { socket?.close() } catch (_: Throwable) {}
        socket = null
        out = null
        din = null
    }
}
