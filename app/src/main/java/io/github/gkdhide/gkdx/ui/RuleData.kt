package io.github.gkdhide.gkdx.ui

import android.content.Context
import android.content.pm.PackageManager
import io.github.gkdhide.gkdx.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import li.songe.json5.Json5
import org.json.JSONArray
import java.util.Base64

/** 规则组条目 */
data class RuleGroupItem(
    val key: Int,
    val name: String,
    val ruleCount: Int,
    val desc: String? = null,
)

/** 某订阅里的某个应用 */
data class RuleAppItem(
    val id: String,
    val label: String?,
    val groups: List<RuleGroupItem>,
) {
    val ruleCount: Int get() = groups.sumOf { it.ruleCount }
}

/** 一份订阅 */
data class SubscriptionItem(
    val index: Int,
    val name: String,
    val version: String,
    val url: String,
    val apps: List<RuleAppItem>,
) {
    val ruleCount: Int get() = apps.sumOf { it.ruleCount }
}

/** 从已下载的订阅数据（SharedPreferences 中的 gzip+base64）解析出可展示的层级结构 */
object RuleRepository {

    @Volatile
    private var cache: List<SubscriptionItem>? = null

    fun invalidate() {
        cache = null
    }

    suspend fun load(context: Context, force: Boolean = false): List<SubscriptionItem> =
        withContext(Dispatchers.IO) {
            if (!force) cache?.let { return@withContext it }
            val sp = context.getSharedPreferences("config", Context.MODE_PRIVATE)
            val raw = sp.getString("subs_store", null)
            if (raw.isNullOrEmpty()) {
                cache = emptyList()
                return@withContext emptyList()
            }
            val result = ArrayList<SubscriptionItem>()
            val pm = context.packageManager
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val url = o.optString("url")
                    val data = o.optString("data")
                    if (data.isEmpty()) continue
                    val text = ZipUtil.gunzip(Base64.getDecoder().decode(data))
                    val root = Json5.parseToJsonElement(text).jsonObject
                    val name = (root["name"] as? JsonPrimitive)?.content ?: "订阅 ${i + 1}"
                    val version = (root["version"] as? JsonPrimitive)?.content ?: ""
                    val apps = ArrayList<RuleAppItem>()
                    (root["apps"] as? JsonArray)?.forEach { appEl ->
                        val app = appEl as? JsonObject ?: return@forEach
                        val id = (app["id"] as? JsonPrimitive)?.content ?: return@forEach
                        val groups = ArrayList<RuleGroupItem>()
                        (app["groups"] as? JsonArray)?.forEach { gEl ->
                            val g = gEl as? JsonObject ?: return@forEach
                            groups.add(
                                RuleGroupItem(
                                    key = (g["key"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                                    name = (g["name"] as? JsonPrimitive)?.content ?: "规则组",
                                    ruleCount = (g["rules"] as? JsonArray)?.size ?: 0,
                                    desc = (g["desc"] as? JsonPrimitive)?.content,
                                )
                            )
                        }
                        apps.add(RuleAppItem(id, appLabel(pm, id), groups))
                    }
                    apps.sortBy { it.id }
                    result.add(SubscriptionItem(i, name, version, url, apps))
                }
            }
            cache = result
            result
        }

    private fun appLabel(pm: PackageManager, pkg: String): String? = runCatching {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()
}
