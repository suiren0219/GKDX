package io.github.gkdhide.gkdx.ui

import android.content.Context
import android.util.Base64
import io.github.gkdhide.gkdx.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import li.songe.json5.Json5
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 下载并解析订阅，结果写入 SharedPreferences 供 Hook 侧 / Root 模式读取 */
object SubscriptionUpdater {

    data class Result(
        val ok: Int,
        val failed: Int,
        val appCount: Int,
        val ruleCount: Int,
        val names: List<String>,
    ) {
        val summary: String
            get() = "$appCount 个应用 / $ruleCount 条规则" + if (failed > 0) "（$failed 个源失败）" else ""
    }

    suspend fun update(context: Context, sources: List<Source>): Result = withContext(Dispatchers.IO) {
        val enabled = sources.filter { it.enabled }
        val store = JSONArray()
        val appIds = LinkedHashSet<String>()
        val names = ArrayList<String>()
        var ruleCount = 0
        var globalCount = 0
        var failed = 0

        for (source in enabled) {
            try {
                val text = download(source.url)
                val root = Json5.parseToJsonElement(text).jsonObject
                names.add((root["name"] as? JsonPrimitive)?.content ?: source.shortName)
                // 全局组（globalGroups，开屏广告规则）数量，供 Hook 侧判断"无规则应用也要装 Hook"
                globalCount += (root["globalGroups"] as? JsonArray)?.size ?: 0
                val apps = root["apps"] as? JsonArray ?: throw IllegalArgumentException("缺少 apps 字段")
                for (appEl in apps) {
                    val app = appEl as? JsonObject ?: continue
                    (app["id"] as? JsonPrimitive)?.content?.let { appIds.add(it) }
                    val groups = app["groups"] as? JsonArray ?: continue
                    for (g in groups) {
                        ruleCount += ((g as? JsonObject)?.get("rules") as? JsonArray)?.size ?: 0
                    }
                }
                store.put(
                    JSONObject().put("url", source.url).put(
                        "data",
                        Base64.encodeToString(ZipUtil.gzip(text), Base64.NO_WRAP),
                    )
                )
            } catch (t: Throwable) {
                failed++
            }
        }

        if (store.length() > 0) {
            context.getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                .putString("subs_store", store.toString())
                // 无规则的应用在进程启动时直接跳过，不安装任何 Hook
                .putString("subs_apps", appIds.joinToString("|", prefix = "|", postfix = "|"))
                .putInt("subs_global", globalCount)
                .putString("subs_meta", "${appIds.size} 应用 / $ruleCount 条规则")
                .putLong("last_update", System.currentTimeMillis())
                .apply()
        }
        Result(enabled.size - failed, failed, appIds.size, ruleCount, names)
    }

    private fun download(url: String): String {
        var current = URL(url)
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "GKDX/1.3")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IllegalStateException("重定向无 Location")
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val bytes = conn.inputStream.use { it.readBytes() }
                return bytes.toString(Charsets.UTF_8)
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("重定向过多")
    }
}
