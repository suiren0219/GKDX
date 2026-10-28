package io.github.gkdhide.gkdx

import android.os.SystemClock
import de.robv.android.xposed.XSharedPreferences

/**
 * 用户在界面里关掉的规则（应用级 / 规则组级），Hook 侧读取。
 * 必须与界面的 DisabledStore 分开：本类引用 Xposed API，只允许在 LSPosed 注入的进程中使用。
 */
object DisabledCache {
    private const val SELF_PACKAGE = "io.github.gkdhide.gkdx"
    private const val KEY = "disabled_keys"

    @Volatile private var cache: Set<String> = emptySet()
    @Volatile private var cacheTime = 0L

    fun get(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - cacheTime < 3000) return cache
        return try {
            val prefs = XSharedPreferences(SELF_PACKAGE, "config")
            prefs.reload()
            val set = prefs.getString(KEY, null)
                ?.split('\n')?.filter { it.isNotBlank() }?.toSet()
                ?: emptySet()
            cache = set
            cacheTime = now
            set
        } catch (t: Throwable) {
            cache
        }
    }
}
