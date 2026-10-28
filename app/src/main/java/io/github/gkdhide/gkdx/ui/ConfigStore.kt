package io.github.gkdhide.gkdx.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 订阅源 */
data class Source(val url: String, val enabled: Boolean = true) {
    /** 展示用短名：取包名部分 */
    val shortName: String
        get() = url.substringAfter("npmmirror.com/").substringAfter("npmjs.com/")
            .substringBefore("/latest").ifEmpty { url }
}

/** 内置订阅源：官方已停更，社区源提供更新更多的规则（全部实测可用） */
object ConfigStore {
    private const val KEY_SOURCES = "sources_json"

    val DEFAULT_SOURCES = listOf(
        Source("https://registry.npmmirror.com/@ganlinte/gkd-subscription/latest/files/dist/ganlin_gkd.json5"),
        Source("https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5"),
        Source("https://registry.npmmirror.com/@adpro/gkd_subscription/latest/files/dist/Adpro_gkd.json5"),
        Source("https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"),
    )

    fun load(context: Context): MutableList<Source> {
        val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_SOURCES, null)
        if (raw != null) {
            runCatching {
                val arr = JSONArray(raw)
                val list = ArrayList<Source>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(Source(o.getString("url"), o.optBoolean("enabled", true)))
                }
                if (list.isNotEmpty()) return list
            }
        }
        // 兼容旧版本的换行分隔配置
        val legacy = sp.getString("urls", null)
        if (!legacy.isNullOrBlank()) {
            val list = legacy.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() }.distinct().map { Source(it) }.toMutableList()
            if (list.isNotEmpty()) return list
        }
        return DEFAULT_SOURCES.toMutableList()
    }

    fun save(context: Context, sources: List<Source>) {
        val arr = JSONArray()
        sources.forEach { arr.put(JSONObject().put("url", it.url).put("enabled", it.enabled)) }
        context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCES, arr.toString())
            // 旧字段同步一份，便于回退旧版本
            .putString("urls", sources.filter { it.enabled }.joinToString("\n") { it.url })
            .apply()
    }

    fun lastUpdateText(context: Context): String {
        val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        val last = sp.getLong("last_update", 0L)
        val meta = sp.getString("subs_meta", "") ?: ""
        if (last == 0L) return "尚未下载订阅"
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
        return fmt.format(java.util.Date(last)) + "  ·  " + meta
    }
}
