package io.github.gkdhide.gkdx

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import io.github.gkdhide.gkdx.ui.DisabledStore
import li.gkd.selector.MatchOptions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 模式引擎 v3：
 * - 双事件源：root logcat 监听 Activity 启动（兼容多种 ROM 日志格式）+ 亮屏时焦点轮询兜底
 * - 只对"有规则的应用"在开屏时序读屏匹配，平时零开销；息屏暂停
 * - 全程诊断打点（Diag），界面上可看到卡在哪一环
 */
object RootEngine {

    @Volatile var running: Boolean = false
        private set

    @Volatile var status: String = "未运行"
        private set

    private var appContext: Context? = null
    private var logcatThread: Thread? = null
    private var logcatProcess: Process? = null
    private var workerThread: Thread? = null
    private var onStatus: ((String) -> Unit)? = null

    private val selfPkg = BuildConfig.APPLICATION_ID
    private val queue = LinkedBlockingQueue<Pair<Long, Pair<String, String>>>()
    private val stopped = AtomicBoolean(false)
    private val tapTimes = HashMap<String, Long>()
    private val actionCounts = HashMap<String, Long>()
    private val eventKeyTime = HashMap<String, Long>()

    private const val SAFETY_POLL_MS = 2500L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun interactive(): Boolean = try {
        (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    } catch (t: Throwable) {
        true
    }

    fun start(callback: (String) -> Unit): String? {
        if (running) return null
        Diag.lastError = ""
        Diag.logcatLines = 0
        Diag.events = 0
        Diag.polls = 0
        Diag.dumpFails = 0
        Diag.subsError = ""
        if (!RootShell.isRoot()) {
            Diag.rootOk = false
            return "未获得 root 权限：${RootShell.lastError ?: "请在 root 管理器中授予本应用"}"
        }
        Diag.rootOk = true
        val subs = try { SubscriptionStore.get(appContext) } catch (t: Throwable) { SafeLog.log(t); null }
        if (subs == null || subs.isEmpty()) {
            Diag.subsError = SubscriptionStore.lastError ?: "未知"
            return "订阅读取失败：${Diag.subsError}"
        }
        Diag.subsApps = subs.appCount
        Diag.subsRules = subs.ruleCount
        Diag.globalGroups = subs.globalGroups.size
        Diag.subsError = ""
        if (subs.appCount == 0 && subs.globalGroups.isEmpty()) {
            return "订阅里没有任何规则，请先在订阅页更新订阅"
        }
        stopped.set(false)
        running = true
        onStatus = callback
        tapTimes.clear()
        actionCounts.clear()
        eventKeyTime.clear()
        queue.clear()
        status = "已启动：${subs.appCount} 个应用 / ${subs.ruleCount} 条规则，等待界面切换…"
        callback(status)
        Diag.line("启动 Root 引擎")
        startLogcatWatcher()
        workerThread = Thread({ workerLoop() }, "GKDX-RootWorker").also {
            it.isDaemon = true
            it.priority = Thread.NORM_PRIORITY - 1
            it.start()
        }
        return null
    }

    fun stop() {
        running = false
        stopped.set(true)
        try { logcatProcess?.destroy() } catch (_: Throwable) {}
        logcatProcess = null
        workerThread?.interrupt()
        workerThread = null
        logcatThread = null
        status = "已停止"
        onStatus?.invoke(status)
        onStatus = null
        Diag.line("停止 Root 引擎")
    }

    private fun log(msg: String) {
        status = msg
        onStatus?.invoke(msg)
        Diag.line(msg)
    }

    // ---------------- 事件源 A：logcat ----------------

    private fun startLogcatWatcher() {
        logcatThread = Thread({
            // 兼容多种 ROM：有的打 cmp=ComponentInfo{pkg/cls}，有的打 cmp=pkg/cls 或 pkg/.cls
            val reCmp = Regex("cmp=([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)")
            while (running && !stopped.get()) {
                try {
                    val p = ProcessBuilder(
                        "su", "-c",
                        "logcat -c 2>/dev/null; logcat -v brief ActivityTaskManager:I ActivityManager:I *:S",
                    ).redirectErrorStream(true).start()
                    logcatProcess = p
                    Diag.line("logcat 事件源已连接")
                    val reader = BufferedReader(InputStreamReader(p.inputStream), 8192)
                    while (running && !stopped.get()) {
                        val line = reader.readLine() ?: break
                        Diag.logcatLines++
                        if (!line.contains("ActivityTaskManager") && !line.contains("ActivityManager")) continue
                        if (!line.contains("START u0") && !line.contains("cmp=")) continue
                        val m = reCmp.find(line) ?: continue
                        var pkg = m.groupValues[1]
                        var act = m.groupValues[2]
                        if (act.startsWith(".")) act = pkg + act
                        enqueueEvent(pkg to act)
                    }
                    try { p.destroy() } catch (_: Throwable) {}
                    if (running) Diag.line("logcat 断开，5 秒后重连")
                } catch (t: Throwable) {
                    Diag.lastError = "logcat: ${t.message ?: t.javaClass.simpleName}"
                }
                if (running && !stopped.get()) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                }
            }
        }, "GKDX-Logcat").also { it.isDaemon = true; it.start() }
    }

    @Synchronized
    private fun enqueueEvent(event: Pair<String, String>) {
        if (!running || !interactive()) return
        val key = "${event.first}|${event.second}"
        val now = SystemClock.elapsedRealtime()
        if (now - (eventKeyTime[key] ?: 0L) < 2000) return
        eventKeyTime[key] = now
        queue.offer(now to event)
    }

    // ---------------- 事件源 B + 处理 ----------------

    private fun workerLoop() {
        var lastSafety = 0L
        while (running && !stopped.get()) {
            try {
                val ev = queue.poll(300, TimeUnit.MILLISECONDS)
                if (ev != null) {
                    handleEvent(ev.second, ev.first)
                    continue
                }
                // 兜底事件源：焦点轮询（多数 ROM 只有 2.5 秒粒度，但确保不会完全无事件）
                val now = SystemClock.elapsedRealtime()
                if (now - lastSafety > SAFETY_POLL_MS && interactive()) {
                    lastSafety = now
                    Diag.polls++
                    queryFocus()?.let { focus ->
                        Diag.focusRaw = "${focus.first}/${focus.second}"
                        // 交给 handleEvent 前先判断：同一界面在 2 秒内不重复处理
                        enqueueEvent(focus)
                    }
                }
            } catch (t: Throwable) {
                Diag.lastError = "worker: ${t.message ?: t.javaClass.simpleName}"
                sleep(800)
            }
        }
    }

    private fun handleEvent(event: Pair<String, String>, eventTime: Long) {
        if (eventTime == 0L) return
        val (pkg, act) = event
        if (pkg == selfPkg || pkg == "android" || pkg == "com.android.systemui") return
        val subs = try { SubscriptionStore.get(appContext) } catch (t: Throwable) { SafeLog.log(t); null } ?: return
        val groups = subs.groupsFor(pkg) + subs.globalGroups
        if (groups.isEmpty()) return
        val disabledKeys = DisabledCache.get(appContext)
        if (DisabledStore.isAppDisabled(disabledKeys, pkg)) return

        Diag.events++
        Diag.lastPkg = pkg
        Diag.lastAct = act
        Diag.lastGroups = groups.size

        log("进入 $act（${groups.size} 组）")

        val adapter = XmlNodeAdapter()
        var hitAny = false
        for (delayMs in longArrayOf(150, 450, 900, 1500, 2300, 3300)) {
            if (!running || stopped.get()) return
            val wait = eventTime + delayMs - SystemClock.elapsedRealtime()
            if (wait > 0) sleep(wait)
            if (wait < -4000) break
            if (!interactive()) return
            val t0 = SystemClock.elapsedRealtime()
            val root = dumpTree()
            Diag.lastDumpMs = SystemClock.elapsedRealtime() - t0
            if (root == null) {
                Diag.dumpFails++
                Diag.line("读屏失败（第 $delayMs ms 采样）")
                continue
            }
            Diag.lastDumpNodes = countNodes(root)
            val hit = findMatch(groups, pkg, act, adapter, root)
            if (hit != null) {
                tap(hit.first, hit.second)
                hitAny = true
            }
        }
        if (hitAny) log("本轮处理完成：$act")
    }

    private fun countNodes(root: XmlNode): Int {
        var n = 0
        val stack = ArrayDeque<XmlNode>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            n++
            cur.children.forEach { stack.addLast(it) }
        }
        return n
    }

    // ---------------- 匹配与动作 ----------------

    private fun findMatch(
        groups: List<CompiledGroup>,
        pkg: String,
        activity: String,
        adapter: XmlNodeAdapter,
        root: XmlNode,
    ): Pair<Int, Int>? {
        val disabledKeys = DisabledCache.get(appContext)
        if (DisabledStore.isAppDisabled(disabledKeys, pkg)) return null
        for (group in groups) {
            if (group.isGlobal) {
                if (DisabledStore.isGlobalGroupDisabled(disabledKeys, group.subIndex, group.key)) continue
            } else if (DisabledStore.isGroupDisabled(disabledKeys, pkg, group.key)) continue
            if (group.excludeActivityIds?.any { activityMatch(it, activity) } == true) continue
            if (group.activityIds?.any { activityMatch(it, activity) } == false) continue
            for ((idx, rule) in group.rules.withIndex()) {
                val stateKey = "$pkg|${if (group.isGlobal) "g" else "a"}${group.subIndex}:${group.key}:$idx:${rule.key}"
                val count = actionCounts[stateKey] ?: 0
                if (rule.actionMaximum > 0 && count >= rule.actionMaximum) continue
                if (SystemClock.elapsedRealtime() - (tapTimes[stateKey] ?: 0L) < 1500) continue
                if (rule.activityIds?.any { activityMatch(it, activity) } == false) continue
                if (rule.excludeActivityIds?.any { activityMatch(it, activity) } == true) continue
                val options = if (rule.quickFind) MatchOptions(fastQuery = true) else MatchOptions.default

                if (rule.excludeMatches.any { ex ->
                        runCatching { adapter.querySelector(root, ex) }.getOrNull() != null
                    }
                ) continue

                val target = if (rule.anyMatches.isNotEmpty()) {
                    rule.anyMatches.firstNotNullOfOrNull { m ->
                        runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                    }
                } else {
                    rule.matches.firstNotNullOfOrNull { m ->
                        runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                    }
                } ?: continue

                val clickable = findClickableAncestor(target) ?: target
                tapTimes[stateKey] = SystemClock.elapsedRealtime()
                actionCounts[stateKey] = count + 1
                val text = clickable.text.ifEmpty { clickable.desc }.take(20)
                Diag.lastHit = "${group.name}/${rule.key ?: idx}${if (text.isNotEmpty()) " [$text]" else ""}"
                Diag.lastTap = "(${clickable.centerX},${clickable.centerY})"
                Diag.lastTapAt = SystemClock.elapsedRealtime()
                log("命中 ${Diag.lastHit} → ${Diag.lastTap}")
                return clickable.centerX to clickable.centerY
            }
        }
        return null
    }

    private fun findClickableAncestor(node: XmlNode): XmlNode? {
        var cur: XmlNode? = node
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.clickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun activityMatch(pattern: String, activity: String): Boolean =
        if (pattern.endsWith(".*")) activity.startsWith(pattern.dropLast(1)) else pattern == activity

    // ---------------- root 命令 ----------------

    private fun queryFocus(): Pair<String, String>? {
        val out = RootShell.exec(
            "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4",
            9000,
        )
        val regex = Regex("([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)")
        for (line in out.lineSequence()) {
            if (!line.contains("mCurrentFocus") && !line.contains("mFocusedApp")) continue
            val m = regex.find(line) ?: continue
            var pkg = m.groupValues[1]
            var act = m.groupValues[2]
            if (act.startsWith(".")) act = pkg + act
            return pkg to act
        }
        return null
    }

    private fun dumpTree(): XmlNode? {
        for (cmd in arrayOf(
            "uiautomator dump --compressed /data/local/tmp/gkdx_ui.xml >/dev/null 2>&1; cat /data/local/tmp/gkdx_ui.xml",
            "uiautomator dump /data/local/tmp/gkdx_ui.xml >/dev/null 2>&1; cat /data/local/tmp/gkdx_ui.xml",
        )) {
            val xml = RootShell.exec(cmd, 20000)
            if (xml.contains("<hierarchy")) return XmlNodeAdapter.parse(xml)
        }
        return null
    }

    private fun tap(x: Int, y: Int) {
        RootShell.exec("input tap $x $y", 8000)
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
