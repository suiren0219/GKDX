package io.github.gkdhide.gkdx

import android.os.SystemClock
import android.util.Base64
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import li.songe.json5.Json5

/** 一条编译后的规则（gkd 订阅 rule 的进程内表达） */
data class CompiledRule(
    val key: Int?,
    val matches: List<Selector>,
    val anyMatches: List<Selector>,
    val excludeMatches: List<Selector>,
    val activityIds: Set<String>?,
    val excludeActivityIds: Set<String>?,
    val action: String,
    val quickFind: Boolean,
    val matchDelay: Long,
    val matchTime: Long,
    val actionMaximum: Int,
    val resetMatch: String,
)

/** 一个编译后的规则组 */
data class CompiledGroup(
    val key: Int,
    val name: String,
    val activityIds: Set<String>?,
    val excludeActivityIds: Set<String>?,
    val quickFind: Boolean,
    val matchDelay: Long,
    val matchTime: Long,
    val actionMaximum: Int,
    val resetMatch: String,
    val rules: List<CompiledRule>,
)

/** 解析 + 编译完成的订阅集合（多订阅源聚合） */
class ParsedSubscription(
    val summary: String,
    val ruleCount: Int,
    val apps: Map<String, List<CompiledGroup>>,
)

/**
 * Hook 侧订阅仓库：从本模块的 SharedPreferences 读取 ConfigActivity 下载好的
 * 订阅数据（gzip+base64），按需解析编译。30 秒缓存，改订阅后热生效。
 */
object SubscriptionStore {
    private const val SELF_PACKAGE = "io.github.gkdhide.gkdx"

    @Volatile private var cache: ParsedSubscription? = null
    @Volatile private var cacheKey: String? = null
    @Volatile private var cacheTime: Long = 0L
    @Volatile private var indexCache: String? = null
    @Volatile private var indexTime: Long = 0L
    private val lock = Any()

    /**
     * 订阅覆盖的应用包名索引（形如 |pkg1|pkg2|），由配置页在下载订阅时写入。
     * 进程启动时用它做一次字符串判断即可决定"要不要装 Hook"，避免为无关应用注入。
     */
    fun appIndex(): String? {
        if (indexCache != null && SystemClock.elapsedRealtime() - indexTime < 30_000) return indexCache
        return try {
            val prefs = XSharedPreferences(SELF_PACKAGE, "config")
            prefs.reload()
            prefs.getString("subs_apps", null)?.also {
                indexCache = it
                indexTime = SystemClock.elapsedRealtime()
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun get(): ParsedSubscription? {
        if (cache != null && SystemClock.elapsedRealtime() - cacheTime < 30_000) return cache
        synchronized(lock) {
            if (cache != null && SystemClock.elapsedRealtime() - cacheTime < 30_000) return cache
            try {
                val prefs = XSharedPreferences(SELF_PACKAGE, "config")
                prefs.reload()
                val raw = prefs.getString("subs_store", null)
                if (raw.isNullOrEmpty()) return cache
                if (raw == cacheKey) {
                    cacheTime = SystemClock.elapsedRealtime()
                    return cache
                }
                val parsed = parseStore(raw)
                cache = parsed
                cacheKey = raw
                cacheTime = SystemClock.elapsedRealtime()
                XposedBridge.log("[GKDX] 订阅已加载: ${parsed.summary}, 应用数=${parsed.apps.size}, 规则数=${parsed.ruleCount}")
                return parsed
            } catch (t: Throwable) {
                XposedBridge.log("[GKDX] 订阅解析失败")
                XposedBridge.log(t)
                return cache
            }
        }
    }

    /** subs_store 内容格式: [{"url":"..","data":"<base64(gzip(json5))>"},..] */
    private fun parseStore(raw: String): ParsedSubscription {
        val entries = Json5.parseToJsonElement(raw).jsonArray
        val apps = HashMap<String, MutableList<CompiledGroup>>()
        var ruleCount = 0
        val summaries = mutableListOf<String>()
        for (entry in entries) {
            val obj = entry.jsonObject
            val b64 = obj["data"]?.jsonPrimitive?.content ?: continue
            val text = ZipUtil.gunzip(Base64.decode(b64, Base64.DEFAULT))
            val (summary, count) = parseSubscription(text, apps)
            summaries += summary
            ruleCount += count
        }
        return ParsedSubscription(summaries.joinToString(" + "), ruleCount, apps)
    }

    private fun parseSubscription(
        text: String,
        apps: MutableMap<String, MutableList<CompiledGroup>>,
    ): Pair<String, Int> {
        val root = Json5.parseToJsonElement(text).jsonObject
        val subName = (root["name"] as? JsonPrimitive)?.content ?: "未命名订阅"
        val appsArr = root["apps"] as? JsonArray
        var count = 0
        if (appsArr != null) {
            for (appEl in appsArr) {
                val app = appEl as? JsonObject ?: continue
                val appId = (app["id"] as? JsonPrimitive)?.content ?: continue
                val groupsArr = app["groups"] as? JsonArray ?: continue
                val groups = mutableListOf<CompiledGroup>()
                for (gEl in groupsArr) {
                    val g = gEl as? JsonObject ?: continue
                    val enable = (g["enable"] as? JsonPrimitive)?.booleanOrNull ?: true
                    if (!enable) continue
                    val group = compileGroup(g) ?: continue
                    groups += group
                    count += group.rules.size
                }
                if (groups.isNotEmpty()) apps.getOrPut(appId) { mutableListOf() }.addAll(groups)
            }
        }
        return "$subName" to count
    }

    private fun compileGroup(g: JsonObject): CompiledGroup? {
        val rulesArr = g["rules"] as? JsonArray ?: return null
        val rules = mutableListOf<CompiledRule>()
        for (rEl in rulesArr) {
            val r = rEl as? JsonObject ?: continue
            val matches = compileAll(r, "matches")
            val anyMatches = compileAll(r, "anyMatches")
            if (matches.isEmpty() && anyMatches.isEmpty()) continue
            rules += CompiledRule(
                key = (r["key"] as? JsonPrimitive)?.content?.toIntOrNull(),
                matches = matches,
                anyMatches = anyMatches,
                excludeMatches = compileAll(r, "excludeMatches"),
                activityIds = strSet(r["activityIds"]),
                excludeActivityIds = strSet(r["excludeActivityIds"]),
                action = (r["action"] as? JsonPrimitive)?.content ?: "click",
                quickFind = (r["quickFind"] ?: g["quickFind"])?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false,
                matchDelay = num(r["matchDelay"] ?: g["matchDelay"]),
                matchTime = num(r["matchTime"] ?: g["matchTime"]),
                actionMaximum = (r["actionMaximum"] ?: g["actionMaximum"])?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0,
                resetMatch = (r["resetMatch"] ?: g["resetMatch"])?.let { (it as? JsonPrimitive)?.content } ?: "activity",
            )
        }
        if (rules.isEmpty()) return null
        return CompiledGroup(
            key = (g["key"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
            name = (g["name"] as? JsonPrimitive)?.content ?: "",
            activityIds = strSet(g["activityIds"]),
            excludeActivityIds = strSet(g["excludeActivityIds"]),
            quickFind = (g["quickFind"] as? JsonPrimitive)?.booleanOrNull ?: false,
            matchDelay = num(g["matchDelay"]),
            matchTime = num(g["matchTime"]),
            actionMaximum = (g["actionMaximum"] as? JsonPrimitive)?.intOrNull ?: 0,
            resetMatch = (g["resetMatch"] as? JsonPrimitive)?.content ?: "activity",
            rules = rules,
        )
    }

    /** 字段值可能是字符串或字符串数组，统一为列表并逐条编译 */
    private fun compileAll(obj: JsonObject, key: String): List<Selector> {
        val el = obj[key] ?: return emptyList()
        val sources = when (el) {
            is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> listOfNotNull(el.content)
            else -> return emptyList()
        }
        return sources.mapNotNull { src ->
            when (val result = Selector.compile(src)) {
                is SelectorCompileResult.Success -> result.value
                is SelectorCompileResult.Failure -> {
                    XposedBridge.log("[GKDX] 选择器编译失败: $src (${result.error.message})")
                    null
                }
            }
        }
    }

    private fun strSet(el: JsonElement?): Set<String>? = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }.toSet().ifEmpty { null }
        is JsonPrimitive -> setOf(el.content)
        else -> null
    }

    private fun num(el: JsonElement?): Long = (el as? JsonPrimitive)?.longOrNull ?: 0L
}
