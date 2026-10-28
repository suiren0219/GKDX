package io.github.gkdhide.gkdx

import android.os.SystemClock
import io.github.gkdhide.gkdx.ui.DisabledStore
import li.gkd.selector.MatchOptions

/**
 * Root 模式引擎：在模块自己的进程内以 root 读屏（uiautomator dump）并点击（input tap）。
 * 不依赖 LSPosed 作用域，也不向任何目标应用注入代码。
 *
 * 代价：轮询式读屏（约 0.5-1s 一次），比进程内 Hook 慢，但能覆盖 WebView 内容。
 */
object RootEngine {

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var status: String = "未运行"
        private set

    private var thread: Thread? = null
    private var onStatus: ((String) -> Unit)? = null
    private val tapTimes = HashMap<String, Long>()
    private val actionCounts = HashMap<String, Int>()

    private val selfPkg = BuildConfig.APPLICATION_ID

    /** 返回 null 表示启动失败（含原因） */
    fun start(callback: (String) -> Unit): String? {
        if (running) return null
        if (!RootShell.isRoot()) return "未获得 root 权限（请先授予本应用 root）"
        val subs = SubscriptionStore.get()
        if (subs == null || subs.apps.isEmpty()) return "尚未下载订阅，请先点\"更新订阅\""
        running = true
        onStatus = callback
        tapTimes.clear()
        actionCounts.clear()
        status = "已启动，正在监听前台应用…"
        callback(status)
        thread = Thread({ loop() }, "GKDX-RootEngine").also {
            it.isDaemon = true
            it.priority = Thread.NORM_PRIORITY - 1
            it.start()
        }
        return null
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        status = "已停止"
        onStatus?.invoke(status)
        onStatus = null
    }

    private fun log(msg: String) {
        status = msg
        onStatus?.invoke(msg)
    }

    private fun loop() {
        var lastPkg = ""
        var lastAct = ""
        var activityStart = 0L
        while (running) {
            val started = SystemClock.elapsedRealtime()
            try {
                val focus = queryFocus()
                if (focus == null) {
                    sleep(1200)
                    continue
                }
                val (pkg, act) = focus
                if (pkg == selfPkg || pkg == "com.android.systemui" || pkg == "android") {
                    sleep(1200)
                    continue
                }
                val subs = SubscriptionStore.get()
                val groups = subs?.apps?.get(pkg)
                if (groups.isNullOrEmpty()) {
                    if (pkg != lastPkg) {
                        lastPkg = pkg
                        lastAct = act
                        actionCounts.clear()
                        log("前台 $pkg 无规则，待机中")
                    }
                    sleep(1200)
                    continue
                }
                if (pkg != lastPkg || act != lastAct) {
                    lastPkg = pkg
                    lastAct = act
                    activityStart = SystemClock.elapsedRealtime()
                    actionCounts.clear()
                    log("进入 $act")
                }
                val root = dumpTree()
                if (root == null) {
                    sleep(600)
                    continue
                }
                val adapter = XmlNodeAdapter()
                val hit = findMatch(groups, pkg, act, adapter, root)
                if (hit != null) {
                    tap(hit.first, hit.second)
                    val remaining = 900 - (SystemClock.elapsedRealtime() - started)
                    if (remaining > 0) sleep(remaining)
                } else {
                    // 刚进入界面的前 4 秒提高轮询频率，之后放慢以省电
                    val idle = if (SystemClock.elapsedRealtime() - activityStart < 4000) 450 else 1200
                    val remaining = idle - (SystemClock.elapsedRealtime() - started)
                    if (remaining > 0) sleep(remaining)
                }
            } catch (t: Throwable) {
                sleep(1000)
            }
        }
    }

    /** 返回 (点 x, 点 y) 或 null */
    private fun findMatch(
        groups: List<CompiledGroup>,
        pkg: String,
        activity: String,
        adapter: XmlNodeAdapter,
        root: XmlNode,
    ): Pair<Int, Int>? {
        val disabledKeys = DisabledCache.get()
        if (DisabledStore.isAppDisabled(disabledKeys, pkg)) return null
        for (group in groups) {
            if (DisabledStore.isGroupDisabled(disabledKeys, pkg, group.key)) continue
            if (group.excludeActivityIds?.any { activityMatch(it, activity) } == true) continue
            if (group.activityIds?.any { activityMatch(it, activity) } == false) continue
            for ((idx, rule) in group.rules.withIndex()) {
                val stateKey = "$pkg:${group.key}:$idx:${rule.key}"
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

                // 引擎与 GKD 一致：不可点击的命中节点向内/向上找可点击父节点
                val clickable = findClickableAncestor(target) ?: target
                tapTimes[stateKey] = SystemClock.elapsedRealtime()
                actionCounts[stateKey] = count + 1
                log("命中规则 ${group.name}/${rule.key ?: idx} → 点击 (${clickable.centerX},${clickable.centerY})")
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
        val xml = RootShell.exec(
            "uiautomator dump /data/local/tmp/gkdx_ui.xml >/dev/null 2>&1; cat /data/local/tmp/gkdx_ui.xml",
            25000,
        )
        if (!xml.contains("<hierarchy")) return null
        return XmlNodeAdapter.parse(xml)
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
