package io.github.gkdhide.gkdx

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import li.gkd.selector.MatchOptions
import io.github.gkdhide.gkdx.ui.DisabledStore
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Root 模式引擎 v2（事件驱动，像 GKD 一样省电）：
 * - 以 root 常驻一条 logcat 流，监听 ActivityTaskManager/ActivityManager 的 START 事件感知界面切换
 * - 只对"有规则的应用"在开屏时序（250/700/1400/2400/3600ms）读屏匹配，平时零开销
 * - 息屏自动暂停；每 60 秒一次兜底焦点检查（仅亮屏时）
 * - 读屏用 uiautomator dump，点击用 input tap，全程不注入任何应用进程
 */
object RootEngine {

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var status: String = "未运行"
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

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun interactive(): Boolean = try {
        (appContext?.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
    } catch (t: Throwable) {
        true
    }

    /** 返回 null 表示启动成功，否则为失败原因 */
    fun start(callback: (String) -> Unit): String? {
        if (running) return null
        if (!RootShell.isRoot()) return "未获得 root 权限（请先授予本应用 root）"
        val subs = try { SubscriptionStore.get(appContext) } catch (t: Throwable) { SafeLog.log(t); null }
        if (subs == null || subs.isEmpty()) return "订阅读取失败，请先\"更新订阅\"后重试"
        stopped.set(false)
        running = true
        onStatus = callback
        tapTimes.clear()
        actionCounts.clear()
        queue.clear()
        status = "已启动，等待界面切换…"
        callback(status)
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
    }

    private fun log(msg: String) {
        status = msg
        onStatus?.invoke(msg)
    }

    // ---------------- 事件来源：logcat 常驻流 ----------------

    private fun startLogcatWatcher() {
        logcatThread = Thread({
            val regex = Regex("cmp=ComponentInfo\\{([a-zA-Z0-9_.]+)/([^}]+)\\}")
            while (running && !stopped.get()) {
                try {
                    // -c 清旧日志；只收 Activity 启动相关标签，噪音最小
                    val p = ProcessBuilder(
                        "su", "-c",
                        "logcat -c; logcat -v brief -s ActivityTaskManager:I ActivityManager:I",
                    ).redirectErrorStream(true).start()
                    logcatProcess = p
                    val reader = BufferedReader(InputStreamReader(p.inputStream), 8192)
                    while (running && !stopped.get()) {
                        val line = reader.readLine() ?: break
                        if (!line.contains("cmp=ComponentInfo")) continue
                        val m = regex.find(line) ?: continue
                        var pkg = m.groupValues[1]
                        var act = m.groupValues[2]
                        if (act.startsWith(".")) act = pkg + act
                        enqueueEvent(pkg to act)
                    }
                    try { p.destroy() } catch (_: Throwable) {}
                } catch (t: Throwable) {
                    SafeLog.log(t)
                }
                if (running && !stopped.get()) {
                    try { Thread.sleep(5000) } catch (_: InterruptedException) {}
                }
            }
        }, "GKDX-Logcat").also { it.isDaemon = true; it.start() }
    }

    private val eventKeyTime = HashMap<String, Long>()

    @Synchronized
    private fun enqueueEvent(event: Pair<String, String>) {
        if (!running || !interactive()) return // 息屏不产生事件
        val key = "${event.first}|${event.second}"
        val now = SystemClock.elapsedRealtime()
        if (now - (eventKeyTime[key] ?: 0L) < 2000) return // 同一界面 2 秒内重复 START 去重
        eventKeyTime[key] = now
        queue.offer(now to event)
    }

    // ---------------- 事件处理 ----------------

    private fun workerLoop() {
        var lastSafetyPoll = 0L
        while (running && !stopped.get()) {
            try {
                val ev = queue.poll(400, TimeUnit.MILLISECONDS)
                if (ev != null) {
                    handleEvent(ev.second, ev.first)
                    continue
                }
                // 兜底：个别 ROM 的 logcat 不打 START，亮屏时每 60 秒查一次焦点
                val now = SystemClock.elapsedRealtime()
                if (now - lastSafetyPoll > 60_000 && interactive()) {
                    lastSafetyPoll = now
                    queryFocus()?.let { enqueueEvent(it) }
                }
            } catch (t: Throwable) {
                SafeLog.log(t)
                sleep(800)
            }
        }
    }

    private fun handleEvent(event: Pair<String, String>, eventTime: Long) {
        // 丢弃积压的过期事件
        while (true) {
            val next = queue.peek() ?: break
            if (eventTime - next.first > 8000) queue.poll() else break
        }
        val (pkg, act) = event
        if (pkg == selfPkg || pkg == "android" || pkg == "com.android.systemui") return
        val subs = try { SubscriptionStore.get(appContext) } catch (t: Throwable) { SafeLog.log(t); null } ?: return
        val groups = subs.groupsFor(pkg) + subs.globalGroups
        if (groups.isEmpty()) return
        val disabledKeys = DisabledCache.get(appContext)
        if (DisabledStore.isAppDisabled(disabledKeys, pkg)) return

        log("进入 $act")

        val adapter = XmlNodeAdapter()
        var hitAny = false
        // 对齐 GKD 的响应节奏：其无障碍服务以 100ms 节流响应窗口变化，
        // 我们用密集的前段采样逼近（dump 本身约 200-500ms，前段间隔取短）
        for (delayMs in longArrayOf(200, 500, 1000, 1600, 2400, 3400)) {
            if (!running || stopped.get()) return
            val wait = eventTime + delayMs - SystemClock.elapsedRealtime()
            if (wait > 0) sleep(wait)
            if (!interactive()) return // 息屏即停
            val root = dumpTree() ?: continue
            val hit = findMatch(groups, pkg, act, adapter, root)
            if (hit != null) {
                tap(hit.first, hit.second)
                hitAny = true
            }
        }
        if (hitAny) log("本轮开屏处理完成：$act")
    }

    // ---------------- 匹配与动作 ----------------

    /** 返回 (点 x, 点 y) 或 null */
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
                val lastTap = tapTimes[stateKey] ?: 0L
                if (SystemClock.elapsedRealtime() - lastTap < 1500) continue
                if (rule.activityIds?.any { activityMatch(it, activity) } == false) continue
                if (rule.excludeActivityIds?.any { activityMatch(it, activity) } == true) continue
                val options = if (rule.quickFind) MatchOptions(fastQuery = true) else MatchOptions.default

                val excluded = rule.excludeMatches.any { ex ->
                    runCatching { adapter.querySelector(root, ex) }.getOrNull() != null
                }
                if (excluded) continue

                val target = if (rule.anyMatches.isNotEmpty()) {
                    rule.anyMatches.firstNotNullOfOrNull { m ->
                        runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                    }
                } else {
                    rule.matches.firstNotNullOfOrNull { m ->
                        runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                    }
                } ?: continue

                // 引擎与 GKD 一致：不可点击的命中节点向上找可点击父节点
                val clickable = findClickableAncestor(target) ?: target
                tapTimes[stateKey] = SystemClock.elapsedRealtime()
                actionCounts[stateKey] = count + 1
                log("命中 ${group.name}/${rule.key ?: idx} → 点击 (${clickable.centerX},${clickable.centerY})")
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
        val out = RootShell.exec("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -4", 12000)
        val regex = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)")
        for (line in out.lineSequence()) {
            if (!line.contains("mCurrentFocus") && !line.contains("mFocusedApp")) continue
            val m = regex.find(line) ?: continue
            val pkg = m.groupValues[1]
            var act = m.groupValues[2]
            if (act.startsWith(".")) act = pkg + act
            return pkg to act
        }
        return null
    }

    private fun dumpTree(): XmlNode? {
        // 部分机型不支持 --compressed，失败则退回普通 dump
        for (cmd in arrayOf(
            "uiautomator dump --compressed /data/local/tmp/gkdx_ui.xml >/dev/null 2>&1; cat /data/local/tmp/gkdx_ui.xml",
            "uiautomator dump /data/local/tmp/gkdx_ui.xml >/dev/null 2>&1; cat /data/local/tmp/gkdx_ui.xml",
        )) {
            val xml = RootShell.exec(cmd, 25000)
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
