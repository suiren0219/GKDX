package io.github.gkdhide.gkdx

import android.os.SystemClock

/** Root 模式运行诊断：界面「诊断」卡片直接读取这些值，方便定位卡在哪一环 */
object Diag {
    @Volatile var rootOk = false
    @Volatile var subsApps = 0
    @Volatile var subsRules = 0
    @Volatile var globalGroups = 0
    @Volatile var subsError = ""

    @Volatile var logcatLines = 0L
    @Volatile var events = 0L
    @Volatile var polls = 0L
    @Volatile var focusRaw = ""

    @Volatile var lastPkg = ""
    @Volatile var lastAct = ""
    @Volatile var lastGroups = 0
    @Volatile var lastDumpNodes = 0
    @Volatile var lastDumpMs = 0L
    @Volatile var dumpFails = 0L

    @Volatile var lastHit = ""
    @Volatile var lastTap = ""
    @Volatile var lastTapAt = 0L

    @Volatile var lastError = ""

    private val ring = ArrayDeque<String>()

    fun line(msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA)
            .format(java.util.Date())
        synchronized(ring) {
            ring.addLast("$t $msg")
            while (ring.size > 80) ring.removeFirst()
        }
        SafeLog.log(msg)
    }

    fun lines(): List<String> = synchronized(ring) { ring.toList() }

    fun agoText(): String {
        val at = lastTapAt
        if (at == 0L) return "—"
        val s = (SystemClock.elapsedRealtime() - at) / 1000
        return "${s}s 前"
    }
}
