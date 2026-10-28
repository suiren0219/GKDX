package io.github.gkdhide.gkdx

import android.util.Log
import de.robv.android.xposed.XposedBridge

/**
 * 安全日志：Xposed 环境写 LSPosed 日志；模块自身进程（Root 模式等）没有
 * XposedBridge 类，首次引用会抛 NoClassDefFoundError，这里统一降级到 logcat。
 */
object SafeLog {
    fun log(msg: String) {
        try {
            XposedBridge.log("[GKDX] $msg")
        } catch (t: Throwable) {
            Log.d("GKDX", msg)
        }
    }

    fun log(t: Throwable) {
        try {
            XposedBridge.log(t)
        } catch (e: Throwable) {
            Log.e("GKDX", "error", t)
        }
    }
}
