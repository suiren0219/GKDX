package io.github.gkdhide.gkdx

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 单一 LSPosed 入口，两种能力并存：
 * 1. 订阅引擎：在作用域应用进程内运行 GKD 订阅规则跳广告（无需无障碍）
 * 2. 无障碍隐藏：对外隐藏 GKD 的无障碍服务状态（若用户同时在使用 GKD 本体）
 *
 * 轻量化：订阅里没有该应用的规则时，直接返回，不安装任何 Hook。
 */
class EngineHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpp.packageName
        if (pkg == BuildConfig.APPLICATION_ID || pkg == "android") return

        // 订阅覆盖判断：没有规则的应用不挂钩子；但存在全局组（开屏规则）时例外，
        // 全局组对所有应用生效，无规则应用也要装
        val index = SubscriptionStore.appIndex()
        val hasRules = index == null || index.contains("|$pkg|")
        if (!hasRules && !SubscriptionStore.hasGlobalGroups()) return

        try {
            TriggerManager.install(pkg)
        } catch (t: Throwable) {
            SafeLog.log("[GKDX] 订阅引擎初始化失败: $pkg")
            SafeLog.log(t)
        }
        try {
            HideHook.install(pkg)
        } catch (t: Throwable) {
            SafeLog.log("[GKDX] 无障碍隐藏初始化失败: $pkg")
            SafeLog.log(t)
        }
    }
}
