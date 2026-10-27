package io.github.gkdhide.gkdx

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * GKD 订阅引擎 LSPosed 入口：在作用域应用进程内安装界面触发器，
 * 用内嵌的 GKD selector 引擎匹配订阅规则并执行点击，全程不需要无障碍权限。
 */
class EngineHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpp: XC_LoadPackage.LoadPackageParam) {
        if (lpp.packageName == BuildConfig.APPLICATION_ID || lpp.packageName == "android") return
        try {
            TriggerManager.install(lpp.packageName)
        } catch (t: Throwable) {
            XposedBridge.log("[GKDX] 初始化失败: ${lpp.packageName}")
            XposedBridge.log(t)
        }
    }
}
