import kotlinx.serialization.json.*
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult
import li.songe.json5.Json5
import java.io.File

fun main(args: Array<String>) {
    val dir = File(if (args.isNotEmpty()) args[0] else "bench-data")
    val files = dir.listFiles { f -> f.name.endsWith(".json5") } ?: return
    var allSelectors = 0
    var compileNs = 0L
    var parseMs = 0L
    for (f in files.sortedBy { it.name }) {
        val t0 = System.nanoTime()
        val root = Json5.parseToJsonElement(f.readText()).jsonObject
        val parse = (System.nanoTime() - t0) / 1_000_000
        parseMs += parse
        val strings = mutableListOf<String>()
        fun collect(g: JsonElement) {
            (g as? JsonObject)?.forEach { (k, v) ->
                if (k == "matches" || k == "anyMatches" || k == "excludeMatches") {
                    when (v) {
                        is JsonArray -> v.forEach { (it as? JsonPrimitive)?.content?.let(strings::add) }
                        is JsonPrimitive -> v.content.let(strings::add)
                        else -> Unit
                    }
                }
            }
        }
        (root["apps"] as? JsonArray)?.forEach { app ->
            ((app as? JsonObject)?.get("groups") as? JsonArray)?.forEach { g ->
                ((g as? JsonObject)?.get("rules") as? JsonArray)?.forEach { r -> collect(r as JsonElement) }
            }
        }
        (root["globalGroups"] as? JsonArray)?.forEach { g ->
            ((g as? JsonObject)?.get("rules") as? JsonArray)?.forEach { r -> collect(r as JsonElement) }
        }
        val c0 = System.nanoTime()
        var ok = 0; var fail = 0
        for (s in strings) {
            when (Selector.compile(s)) {
                is SelectorCompileResult.Success -> ok++
                is SelectorCompileResult.Failure -> fail++
            }
        }
        val compile = (System.nanoTime() - c0) / 1_000_000
        compileNs += System.nanoTime() - c0
        allSelectors += strings.size
        println("%-16s 解析 %4d ms  编译 %3d 个选择器 %4d ms  (失败 %d)".format(f.name, parse, strings.size, compile, fail))
    }
    println("合计: 选择器 $allSelectors 个, JSON5 解析 ${parseMs} ms, 选择器编译 ${compileNs / 1_000_000} ms")
}
