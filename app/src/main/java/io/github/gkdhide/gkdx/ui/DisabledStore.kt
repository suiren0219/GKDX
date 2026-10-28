package io.github.gkdhide.gkdx.ui

import android.content.Context

/**
 * 用户在界面里逐项关掉的规则：应用级 "app:<pkg>"、规则组级 "group:<pkg>#<groupKey>"。
 * 本类只供界面进程使用；Hook 侧读取走 io.github.gkdhide.gkdx.DisabledCache。
 */
object DisabledStore {
    private const val KEY = "disabled_keys"

    const val PREFIX_APP = "app:"
    const val PREFIX_GROUP = "group:"

    fun appKey(pkg: String) = "$PREFIX_APP$pkg"

    fun groupKey(pkg: String, groupKey: Int) = "$PREFIX_GROUP$pkg#$groupKey"

    fun load(context: Context): MutableSet<String> {
        val raw = context.getSharedPreferences("config", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return mutableSetOf()
        return raw.split('\n').filter { it.isNotBlank() }.toMutableSet()
    }

    fun save(context: Context, keys: Set<String>) {
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
            .putString(KEY, keys.joinToString("\n"))
            .apply()
    }

    fun isAppDisabled(keys: Set<String>, pkg: String) = keys.contains(appKey(pkg))

    fun isGroupDisabled(keys: Set<String>, pkg: String, groupKey: Int) =
        keys.contains(groupKey(pkg, groupKey))
}
