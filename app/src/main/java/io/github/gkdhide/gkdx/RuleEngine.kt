package io.github.gkdhide.gkdx

import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.gkdhide.gkdx.ui.DisabledStore
import li.gkd.selector.MatchOptions

/**
 * 规则评估与动作执行。语义上做了 v1 简化：
 * - 组内按规则顺序匹配，每轮每组最多触发一条规则
 * - actionMaximum 计数默认按 Activity 重置（resetMatch=app 时跨 Activity 保留）
 * - matchTime 表示 Activity 恢复后多久内规则有效
 */
object RuleEngine {

    private class RuleState {
        var actionCount = 0
        var firstSeenAt = 0L
        var activityKey: Any? = null
    }

    private val states = HashMap<String, RuleState>()

    fun evaluate(pkg: String, activity: Activity?, roots: List<View>): Int {
        val subs = SubscriptionStore.get() ?: return 0
        val groups = subs.groupsFor(pkg) + subs.globalGroupsFor(pkg)
        if (groups.isEmpty()) return 0
        if (roots.isEmpty()) return 0
        val disabledKeys = DisabledCache.get()
        if (DisabledStore.isAppDisabled(disabledKeys, pkg)) return 0
        val adapter = ViewNodeAdapter()
        val activityName = activity?.javaClass?.name
        val activityKey: Any = activity ?: pkg
        var triggered = 0
        for (group in groups) {
            if (group.isGlobal) {
                if (DisabledStore.isGlobalGroupDisabled(disabledKeys, group.subIndex, group.key)) continue
            } else if (DisabledStore.isGroupDisabled(disabledKeys, pkg, group.key)) continue
            if (activityName != null) {
                if (group.excludeActivityIds?.any { activityMatch(it, activityName) } == true) continue
                if (group.activityIds?.any { activityMatch(it, activityName) } == false) continue
            }
            for ((idx, rule) in group.rules.withIndex()) {
                val stateKey = "$pkg|${if (group.isGlobal) "g" else "a"}${group.subIndex}:${group.key}:$idx:${rule.key}"
                val st = states.getOrPut(stateKey) { RuleState() }
                if (st.activityKey !== activityKey) {
                    // resetMatch=app 时跨 Activity 保留计数，其余情况重置
                    if (rule.resetMatch != "app" || st.activityKey == null) {
                        st.actionCount = 0
                        st.firstSeenAt = 0L
                    }
                    st.activityKey = activityKey
                }
                if (rule.actionMaximum > 0 && st.actionCount >= rule.actionMaximum) continue
                if (activityName != null) {
                    if (rule.excludeActivityIds?.any { activityMatch(it, activityName) } == true) continue
                    if (rule.activityIds?.any { activityMatch(it, activityName) } == false) continue
                }
                if (rule.matchTime > 0) {
                    if (st.firstSeenAt == 0L) st.firstSeenAt = SystemClock.uptimeMillis()
                    if (SystemClock.uptimeMillis() - st.firstSeenAt > rule.matchTime) continue
                }
                val options =
                    if (rule.quickFind) MatchOptions(fastQuery = true) else MatchOptions.default
                var hit: View? = null
                for (root in roots) {
                    if (rule.excludeMatches.any { ex ->
                            runCatching { adapter.querySelector(root, ex) }.getOrNull() != null
                        }
                    ) continue
                    hit = if (rule.anyMatches.isNotEmpty()) {
                        rule.anyMatches.firstNotNullOfOrNull { m ->
                            runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                        }
                    } else {
                        rule.matches.firstNotNullOfOrNull { m ->
                            runCatching { adapter.querySelector(root, m, options) }.getOrNull()
                        }
                    }
                    if (hit != null) break
                }
                if (hit != null) {
                    st.actionCount++
                    performAction(rule.action, hit, activity)
                    triggered++
                    break
                }
            }
        }
        return triggered
    }

    /** GKD 的 activityId 支持全类名或以 .* 结尾的前缀匹配 */
    private fun activityMatch(pattern: String, activityName: String): Boolean =
        if (pattern.endsWith(".*")) activityName.startsWith(pattern.dropLast(1))
        else pattern == activityName

    private fun performAction(action: String, target: View, activity: Activity?) {
        try {
            when (action) {
                "longClick" -> target.performLongClick()
                "back" -> activity?.let { a -> runCatching { a.onBackPressed() } }
                "clickCenter" -> click(target, center = true)
                "click", "clickNode", "clickNodeFront" -> click(target, center = false)
                else -> click(target, center = false)
            }
        } catch (t: Throwable) {
            SafeLog.log("[GKDX] 动作执行失败: $action")
            SafeLog.log(t)
        }
    }

    private fun click(v: View, center: Boolean) {
        // 可点击节点直接 performClick；否则向目标中心合成一次真实点击事件
        if (!center && v.isClickable && v.isEnabled) {
            v.performClick()
            return
        }
        val root = v.rootView
        val tLoc = IntArray(2)
        v.getLocationOnScreen(tLoc)
        val rLoc = IntArray(2)
        root.getLocationOnScreen(rLoc)
        val x = (tLoc[0] + v.width / 2).toFloat() - rLoc[0]
        val y = (tLoc[1] + v.height / 2).toFloat() - rLoc[1]
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        runCatching { root.dispatchTouchEvent(down) }
        down.recycle()
        val up = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_UP, x, y, 0)
        root.postDelayed({
            runCatching { root.dispatchTouchEvent(up) }
            up.recycle()
        }, 60)
    }
}
