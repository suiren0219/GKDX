package io.github.gkdhide.gkdx

import android.content.Context
import io.github.gkdhide.gkdx.ui.DisabledStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.gkd.selector.MatchOptions

/**
 * 一键自检：把 Root 模式的整条链路在真机上跑一遍并给出每步结果。
 * 第 5 步会等 4 秒（让用户切到广告界面），然后读屏 + 用真实订阅规则现场匹配。
 */
object SelfTest {

    data class Step(val ok: Boolean, val name: String, val detail: String)

    suspend fun run(context: Context, onStep: (Step) -> Unit): List<Step> =
        withContext(Dispatchers.IO) {
            val steps = ArrayList<Step>()
            fun add(ok: Boolean, name: String, detail: String) {
                val s = Step(ok, name, detail)
                steps.add(s)
                onStep(s)
            }

            // 1. root
            val rootOk = RootShell.isRoot()
            add(rootOk, "root", if (rootOk) "uid=0 授权正常" else "失败：${RootShell.lastError ?: "未授权"}")
            if (!rootOk) return@withContext steps

            // 2. 前台焦点
            val focusRaw = RootShell.exec(
                "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -3", 9000,
            ).trim()
            add(focusRaw.isNotEmpty(), "前台焦点",
                if (focusRaw.isEmpty()) "dumpsys 没返回（ROM 限制？）" else focusRaw.replace('\n', ' ').take(160))

            // 3. 订阅
            val subs = try { SubscriptionStore.get(context) } catch (t: Throwable) { SafeLog.log(t); null }
            add(subs != null && !subs.isEmpty(), "订阅",
                if (subs == null || subs.isEmpty()) "失败：${SubscriptionStore.lastError ?: "空"}"
                else "${subs.appCount} 应用 / ${subs.ruleCount} 条规则 / 全局组 ${subs.globalGroups.size}（排除 ${subs.globalExcludedCount} 个应用）")

            // 4. 当前应用有没有规则
            val focusPkg = Regex("([A-Za-z0-9_.]+)/").find(focusRaw)?.groupValues?.get(1) ?: ""
            val groupCount = if (subs != null && focusPkg.isNotEmpty())
                (subs.groupsFor(focusPkg) + subs.globalGroupsFor(focusPkg)).size else 0
            add(groupCount > 0, "当前应用规则",
                if (groupCount > 0) "$focusPkg → $groupCount 组规则" else "$focusPkg → 订阅里没有它的规则（或全局组被排除）")

            // 5. 倒计时后读当前屏幕 + 现场匹配
            add(true, "读屏提示", "4 秒后读取屏幕，请立即切换到广告界面！")
            Thread.sleep(4000)
            val t0 = System.currentTimeMillis()
            val root = RootEngine.dumpTree()
            if (root == null) {
                add(false, "读屏(uiautomator)", "失败——这台 ROM 的 dump 不可用/被限制；Root 模式在此设备无法工作")
                return@withContext steps
            }
            val nodes = RootEngine.countNodes(root)
            val ms = System.currentTimeMillis() - t0
            add(true, "读屏(uiautomator)", "$nodes 个节点，耗时 ${ms}ms")

            // 6. 现场匹配
            if (subs == null || focusPkg.isEmpty()) {
                add(false, "规则匹配", "前置信息缺失，跳过")
                return@withContext steps
            }
            val groups = subs.groupsFor(focusPkg) + subs.globalGroupsFor(focusPkg)
            val adapter = XmlNodeAdapter()
            val disabled = DisabledCache.get(context)
            val hits = ArrayList<String>()
            var scanned = 0
            var appDisabled = false
            for (group in groups) {
                if (DisabledStore.isAppDisabled(disabled, focusPkg)) { appDisabled = true; break }
                if (DisabledStore.isGroupDisabled(disabled, focusPkg, group.key)) continue
                for ((idx, rule) in group.rules.withIndex()) {
                    scanned++
                    val options = if (rule.quickFind) MatchOptions(fastQuery = true) else MatchOptions.default
                    val target = if (rule.anyMatches.isNotEmpty()) {
                        rule.anyMatches.firstNotNullOfOrNull { m ->
                            runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                        }
                    } else {
                        rule.matches.firstNotNullOfOrNull { m ->
                            runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                        }
                    }
                    if (target != null) {
                        hits.add("${group.name}#${rule.key ?: idx} → ${target.className.substringAfterLast('.')} " +
                                "text='${target.text.take(14)}' vid='${target.vid.take(20)}' " +
                                "[${target.left},${target.top}][${target.right},${target.bottom}]")
                    }
                }
            }
            if (appDisabled) {
                add(false, "规则匹配", "该应用已被你在应用列表里手动关闭")
            } else {
                add(true, "规则匹配", "扫描 $scanned 条规则，命中 ${hits.size} 条" +
                        if (hits.isEmpty()) "（当前屏幕没有可跳过元素，或规则未覆盖）" else "")
                hits.take(4).forEach { add(true, "命中详情", it) }
            }
            steps
        }
}
