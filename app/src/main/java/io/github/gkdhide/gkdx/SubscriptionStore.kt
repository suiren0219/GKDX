package io.github.gkdhide.gkdx

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import de.robv.android.xposed.XSharedPreferences
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
    val subIndex: Int,
    val isGlobal: Boolean,
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

/** 编译前的轻量草稿：只保存字符串与标量，避免为所有应用持有选择器对象 */
data class RuleDraft(
    val key: Int?,
    val matches: List<String>,
    val anyMatches: List<String>,
    val excludeMatches: List<String>,
    val activityIds: Set<String>?,
    val excludeActivityIds: Set<String>?,
    val action: String,
    val quickFind: Boolean,
    val matchDelay: Long,
    val matchTime: Long,
    val actionMaximum: Int,
    val resetMatch: String,
)

data class GroupDraft(
    val subIndex: Int,
    val isGlobal: Boolean,
    val key: Int,
    val name: String,
    val activityIds: Set<String>?,
    val excludeActivityIds: Set<String>?,
    val quickFind: Boolean,
    val matchDelay: Long,
    val matchTime: Long,
    val actionMaximum: Int,
    val resetMatch: String,
    val rules: List<RuleDraft>,
)

/** 解析完成的订阅集合（多订阅源聚合） */
class ParsedSubscription(
    val summary: String,
    val ruleCount: Int,
    /** 全局组（globalGroups，对所有应用生效，开屏广告规则都在这里），量小，进程内直接编译 */
    val globalGroups: List<CompiledGroup>,
    /** 每个应用未编译的规则组草稿，按需编译 */
    private val drafts: Map<String, List<GroupDraft>>,
) {
    private val compiled = HashMap<String, List<CompiledGroup>>()

    /** 取某应用的规则组（含懒编译缓存）；没有则返回空表 */
    fun groupsFor(pkg: String): List<CompiledGroup> {
        compiled[pkg]?.let { return it }
        val draft = drafts[pkg] ?: return emptyList()
        synchronized(this) {
            compiled[pkg]?.let { return it }
            val groups = draft.mapNotNull { SubscriptionStore.compileGroup(it) }
            // 控制内存：缓存超过 24 个应用时全部重置，下次用时再编译
            if (compiled.size >= 24) compiled.clear()
            compiled[pkg] = groups
            return groups
        }
    }

    /** 是否一条规则都没有（连全局组也没有） */
    fun isEmpty(): Boolean = globalGroups.isEmpty() && drafts.isEmpty()
}

/**
 * Hook 侧订阅仓库：从本模块的 SharedPreferences 读取 ConfigActivity 下载好的
 * 订阅数据（gzip+base64），全局组直接编译，应用组按需懒编译。30 秒缓存。
 */
object SubscriptionStore {
    private const val SELF_PACKAGE = "io.github.gkdhide.gkdx"

    @Volatile private var cache: ParsedSubscription? = null
    @Volatile private var cacheKey: String? = null
    @Volatile private var cacheTime: Long = 0L
    @Volatile private var indexCache: String? = null
    @Volatile private var indexTime: Long = 0L
    @Volatile private var globalCountCache: Int = -1
    @Volatile private var globalCountTime: Long = 0L
    private val lock = Any()

    /**
     * 订阅覆盖的应用包名索引（形如 |pkg1|pkg2|）。没有规则的应用在进程启动时
     * 直接跳过、不装任何 Hook；但存在全局组时例外（全局组对所有应用生效）。
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

    /** 订阅里是否存在全局组（globalGroups），决定无规则应用是否也要装 Hook */
    fun hasGlobalGroups(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (globalCountCache >= 0 && now - globalCountTime < 30_000) return globalCountCache > 0
        return try {
            val prefs = XSharedPreferences(SELF_PACKAGE, "config")
            prefs.reload()
            globalCountCache = prefs.getInt("subs_global", 0)
            globalCountTime = now
            globalCountCache > 0
        } catch (t: Throwable) {
            globalCountCache > 0
        }
    }

    fun get(context: Context? = null): ParsedSubscription? {
        if (cache != null && SystemClock.elapsedRealtime() - cacheTime < 30_000) return cache
        synchronized(lock) {
            if (cache != null && SystemClock.elapsedRealtime() - cacheTime < 30_000) return cache
            try {
                val raw = readRaw(context)
                if (raw.isNullOrEmpty()) return cache
                if (raw == cacheKey) {
                    cacheTime = SystemClock.elapsedRealtime()
                    return cache
                }
                val parsed = parseStore(raw)
                cache = parsed
                cacheKey = raw
                cacheTime = SystemClock.elapsedRealtime()
                SafeLog.log("[GKDX] 订阅已加载: ${parsed.summary}, 全局组=${parsed.globalGroups.size}, 规则数=${parsed.ruleCount}")
                return parsed
            } catch (t: Throwable) {
                SafeLog.log("[GKDX] 订阅解析失败")
                SafeLog.log(t)
                return cache
            }
        }
    }

    /**
     * 读原始订阅数据。两条通道按进程环境自动选择：
     * - 模块自身进程（Root 模式）：直接读自己的 SharedPreferences，绝不碰 Xposed 类
     * - 被 LSPosed 注入的进程：走 XSharedPreferences
     */
    private fun readRaw(context: Context?): String? {
        if (context != null) {
            return try {
                context.getSharedPreferences("config", Context.MODE_PRIVATE)
                    .getString("subs_store", null)
            } catch (t: Throwable) {
                SafeLog.log(t)
                null
            }
        }
        return try {
            val prefs = XSharedPreferences(SELF_PACKAGE, "config")
            prefs.reload()
            prefs.getString("subs_store", null)
        } catch (t: Throwable) {
            SafeLog.log(t)
            null
        }
    }

    /** subs_store 内容格式: [{"url":"..","data":"<base64(gzip(json5))>"},..] */
    private fun parseStore(raw: String): ParsedSubscription {
        val entries = Json5.parseToJsonElement(raw).jsonArray
        val drafts = HashMap<String, MutableList<GroupDraft>>()
        val globalCompiled = ArrayList<CompiledGroup>()
        var ruleCount = 0
        val summaries = mutableListOf<String>()
        for ((subIndex, entry) in entries.withIndex()) {
            val obj = entry.jsonObject
            val b64 = obj["data"]?.jsonPrimitive?.content ?: continue
            val text = ZipUtil.gunzip(Base64.decode(b64, Base64.DEFAULT))
            val (summary, count) = parseSubscription(text, subIndex, drafts, globalCompiled)
            summaries += summary
            ruleCount += count
        }
        return ParsedSubscription(summaries.joinToString(" + "), ruleCount, globalCompiled, drafts)
    }

    private fun parseSubscription(
        text: String,
        subIndex: Int,
        drafts: MutableMap<String, MutableList<GroupDraft>>,
        globalCompiled: MutableList<CompiledGroup>,
    ): Pair<String, Int> {
        val root = Json5.parseToJsonElement(text).jsonObject
        val subName = (root["name"] as? JsonPrimitive)?.content ?: "未命名订阅"
        var count = 0

        // 1) 全局组：官方与社区订阅的开屏广告规则都在这里，对所有应用生效
        (root["globalGroups"] as? JsonArray)?.forEach { gEl ->
            val g = gEl as? JsonObject ?: return@forEach
            if (((g["enable"] as? JsonPrimitive)?.booleanOrNull ?: true)) {
                groupDraft(g, subIndex, true)?.let { draft ->
                    compileGroup(draft)?.let {
                        globalCompiled.add(it)
                        count += it.rules.size
                    }
                }
            }
        }

        // 2) 应用组：只存草稿，命中哪个应用再编译哪个
        (root["apps"] as? JsonArray)?.forEach { appEl ->
            val app = appEl as? JsonObject ?: return@forEach
            val appId = (app["id"] as? JsonPrimitive)?.content ?: return@forEach
            (app["groups"] as? JsonArray)?.forEach { gEl ->
                val g = gEl as? JsonObject ?: return@forEach
                if (((g["enable"] as? JsonPrimitive)?.booleanOrNull ?: true)) {
                    groupDraft(g, subIndex, false)?.let { draft ->
                        if (draft.rules.isNotEmpty()) {
                            drafts.getOrPut(appId) { mutableListOf() }.add(draft)
                            count += draft.rules.size
                        }
                    }
                }
            }
        }
        return subName to count
    }

    /** 从订阅 JSON 里抽规则组草稿（不编译选择器） */
    private fun groupDraft(g: JsonObject, subIndex: Int, isGlobal: Boolean): GroupDraft? {
        val rulesArr = g["rules"] as? JsonArray ?: return null
        val rules = mutableListOf<RuleDraft>()
        for (rEl in rulesArr) {
            val r = rEl as? JsonObject ?: continue
            val matches = strList(r["matches"])
            val anyMatches = strList(r["anyMatches"])
            if (matches.isEmpty() && anyMatches.isEmpty()) continue
            rules += RuleDraft(
                key = (r["key"] as? JsonPrimitive)?.content?.toIntOrNull(),
                matches = matches,
                anyMatches = anyMatches,
                excludeMatches = strList(r["excludeMatches"]),
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
        return GroupDraft(
            subIndex = subIndex,
            isGlobal = isGlobal,
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

    /** 草稿 → 编译后的组（懒编译入口） */
    internal fun compileGroup(d: GroupDraft): CompiledGroup? {
        if (d.rules.isEmpty()) return null
        val rules = mutableListOf<CompiledRule>()
        for (rd in d.rules) {
            val matches = compileAll(rd.matches)
            val anyMatches = compileAll(rd.anyMatches)
            if (matches.isEmpty() && anyMatches.isEmpty()) continue
            rules += CompiledRule(
                key = rd.key,
                matches = matches,
                anyMatches = anyMatches,
                excludeMatches = compileAll(rd.excludeMatches),
                activityIds = rd.activityIds,
                excludeActivityIds = rd.excludeActivityIds,
                action = rd.action,
                quickFind = rd.quickFind,
                matchDelay = rd.matchDelay,
                matchTime = rd.matchTime,
                actionMaximum = rd.actionMaximum,
                resetMatch = rd.resetMatch,
            )
        }
        if (rules.isEmpty()) return null
        return CompiledGroup(
            subIndex = d.subIndex,
            isGlobal = d.isGlobal,
            key = d.key,
            name = d.name,
            activityIds = d.activityIds,
            excludeActivityIds = d.excludeActivityIds,
            quickFind = d.quickFind,
            matchDelay = d.matchDelay,
            matchTime = d.matchTime,
            actionMaximum = d.actionMaximum,
            resetMatch = d.resetMatch,
            rules = rules,
        )
    }

    private fun compileAll(sources: List<String>): List<Selector> = sources.mapNotNull { src ->
        when (val result = Selector.compile(src)) {
            is SelectorCompileResult.Success -> result.value
            is SelectorCompileResult.Failure -> {
                SafeLog.log("[GKDX] 选择器编译失败: $src (${result.error.message})")
                null
            }
        }
    }

    private fun strList(el: JsonElement?): List<String> = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOfNotNull(el.content)
        else -> emptyList()
    }

    private fun strSet(el: JsonElement?): Set<String>? = when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content }.toSet().ifEmpty { null }
        is JsonPrimitive -> setOf(el.content)
        else -> null
    }

    private fun num(el: JsonElement?): Long = (el as? JsonPrimitive)?.longOrNull ?: 0L
}
