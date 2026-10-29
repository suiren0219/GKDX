import li.gkd.selector.*
import li.songe.json5.Json5
import kotlinx.serialization.json.*
import java.io.File

/** 用真实订阅的开屏规则，验证能否匹配到典型的"跳过"按钮节点 */
private class TestNode(val cls: String, val text: String = "", val desc: String = "", val vid: String = "",
                       val clickable: Boolean = false, val children: List<TestNode> = emptyList()) {
    var parent: TestNode? = null
    fun attach(p: TestNode?) { parent = p; children.forEach { it.attach(this) } }
    val id get() = vid
}

private class TestAdapter : NodeAdapter<TestNode>() {
    override fun getName(node: TestNode) = node.cls
    override fun getChildCount(node: TestNode) = node.children.size
    override fun getChild(node: TestNode, index: Int) = node.children.getOrNull(index)
    override fun getParent(node: TestNode) = node.parent
    override fun getNodeKey(node: TestNode): Any = node
    override fun getAttr(target: Any, name: String): Any? {
        val n = target as TestNode
        return when (name) {
            "text" -> n.text.ifEmpty { null }
            "desc" -> n.desc.ifEmpty { null }
            "vid" -> n.vid.ifEmpty { null }
            "id" -> n.vid.ifEmpty { null }
            "visibleToUser" -> true
            "clickable" -> n.clickable
            "childCount" -> n.children.size
            "index" -> n.parent?.children?.indexOf(n) ?: 0
            "depth" -> 0
            else -> null
        }
    }
}

fun main() {
    val s = File("D:/ai/subs-test/official.json5").readText()
    val root = Json5.parseToJsonElement(s).jsonObject
    val g = (root["globalGroups"] as JsonArray)[0] as JsonObject
    val rules = g["rules"] as JsonArray
    // 官方全局组规则 0 与规则 1
    val selectors = mutableListOf<Pair<String,String>>()
    for (r in rules) {
        val o = r as JsonObject
        val m = o["matches"] as? JsonPrimitive ?: continue
        selectors.add((o["key"]?.jsonPrimitive?.content ?: "?") to m.content)
    }
    println("官方全局组选择器 ${selectors.size} 条\n")

    // 典型开屏界面 1：原生 TextView "跳过 3"
    val skipBtn = TestNode("android.widget.TextView", text = "跳过 3", clickable = true)
    val layout = TestNode("android.widget.FrameLayout", children = listOf(
        TestNode("android.widget.ImageView"),
        TestNode("android.widget.LinearLayout", children = listOf(skipBtn)),
    ))
    layout.attach(null)

    // 典型开屏界面 2：CountDownTextView，无文字，vid 含 skip
    val cd = TestNode("android.widget.TextView", vid = "com.xxx:id/skip_view", clickable = true)
    val layout2 = TestNode("android.widget.FrameLayout", children = listOf(cd))
    layout2.attach(null)

    // 典型开屏界面 3：content-desc="跳过广告"
    val d = TestNode("android.widget.ImageView", desc = "跳过广告", clickable = true)
    val layout3 = TestNode("android.widget.FrameLayout", children = listOf(d))
    layout3.attach(null)

    val adapter = TestAdapter()
    val cases = listOf("原生 TextView '跳过 3'" to layout, "vid=skip_view 无文字" to layout2, "desc=跳过广告" to layout3)
    for ((name, rootNode) in cases) {
        var hit: String? = null
        for ((key, src) in selectors) {
            val sel = when (val r = Selector.compile(src)) {
                is SelectorCompileResult.Success -> r.value
                is SelectorCompileResult.Failure -> { println("  编译失败[$key]: ${r.error.message}"); continue }
            }
            val found = adapter.querySelector(rootNode, sel)
            if (found != null) { hit = "规则key=$key -> ${found.cls} text='${found.text}' vid='${found.vid}'"; break }
        }
        println("%-22s => %s".format(name, hit ?: "未命中 ❌"))
    }
}
