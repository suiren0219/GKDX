package io.github.gkdhide.gkdx

import android.content.Context
import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences

/**
 * 用户在界面里关掉的规则（应用级 / 规则组级）读取。
 * - 传入 context（模块自身进程 / Root 模式）：直读自己的 SharedPreferences
 * - 不传（被 LSPosed 注入的进程）：走 XSharedPreferences
 * 必须与界面的 DisabledStore 分开：本类引用 Xposed 类，仅注入进程可用该通道。
 */
object DisabledCache {
    private const val SELF_PACKAGE = "io.github.gkdhide.gkdx"
    private const val KEY = "disabled_keys"

    @Volatile private var cache: Set<String> = emptySet()
    @Volatile private var cacheTime = 0L

    fun get(context: Context? = null): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cacheTime < 3000) return cache
        val raw: String? = if (context != null) {
            try {
                context.getSharedPreferences("config", Context.MODE_PRIVATE).getString(KEY, null)
            } catch (t: Throwable) {
                null
            }
        } else {
            try {
                val prefs = XSharedPreferences(SELF_PACKAGE, "config")
                prefs.reload()
                prefs.getString(KEY, null)
            } catch (t: Throwable) {
                SafeLog.log(t)
                null
            }
        }
        val set = raw?.split('\n')?.filter { it.isNotBlank() }?.toSet() ?: cache
        cache = set
        cacheTime = now
        return set
    }
}
