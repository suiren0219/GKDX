import li.gkd.selector.NodeAdapter
import li.gkd.selector.Selector
import li.gkd.selector.SelectorCompileResult

// 与 app 的 DumpsysTree.kt 相同的正则（内联验证解析逻辑）
private val lineRe = Regex("^([ |\\\\+`-]*)([a-zA-Z0-9_.$]+)\\{([^}]*)\\}")
private val boundsRe = Regex("(-?\\d+),(-?\\d+)-(-?\\d+),(-?\\d+)")
private val idRe = Regex("[a-zA-Z0-9_.]+:id/([A-Za-z0-9_.]+)")

private class N(val cls: String, var text: String? = null, var vid: String? = null,
                var clickable: Boolean = false, var w: Int = 0, var h: Int = 0) {
    val children = ArrayList<N>()
    var parent: N? = null
    fun link(p: N?) {
        parent = p
        children.forEach { it.link(this) }
    }
}

private class A : NodeAdapter<N>() {
    override fun getName(node: N) = node.cls
    override fun getChildCount(node: N) = node.children.size
    override fun getChild(node: N, i: Int) = node.children.getOrNull(i)
    override fun getParent(node: N) = node.parent
    override fun getNodeKey(node: N) = node
    override fun getAttr(target: Any, name: String): Any? {
        val n = target as N
        return when (name) {
            "vid" -> n.vid
            "id" -> n.vid
            "text" -> n.text
            "desc" -> null
            "clickable" -> n.clickable
            "visibleToUser" -> n.w > 0 && n.h > 0
            "childCount" -> n.children.size
            "index" -> n.parent?.children?.indexOf(n) ?: 0
            "depth" -> 0
            else -> null
        }
    }
}

private val SAMPLE = """
    Window #0 Window{abc u0 com.badapp/com.badapp.SplashActivity}
      View Hierarchy:
        com.android.internal.policy.DecorView{1a2b3 V.E...... R.....D 0,0-1080,2400}
          android.widget.LinearLayout{2b3c4 V.E...... R.....D 0,0-1080,2400}
            android.widget.TextView{4d5e6 V.ED..C.. ........ 840,60-1064,150 #7f080123 app:id/tt_splash_skip_btn}
            android.view.View{5e6f7 V.E...... ........ 0,0-0,0}
"""

fun main() {
    // 1) 解析验证（与 DumpsysTree.parse 相同的逻辑）
    val started = SAMPLE.indexOf("View Hierarchy:")
    val root = N("root", w = 1, h = 1)
    val stack = ArrayDeque<Pair<Int, N>>()
    stack.addLast(-1 to root)
    var count = 0
    for (raw in SAMPLE.substring(started).lines()) {
        val m = lineRe.find(raw) ?: continue
        val indent = m.groupValues[1].length
        val inside = m.groupValues[3]
        val n = N(m.groupValues[2])
        boundsRe.find(inside)?.let { b ->
            val l = b.groupValues[1].toIntOrNull() ?: 0
            val t = b.groupValues[2].toIntOrNull() ?: 0
            val r = b.groupValues[3].toIntOrNull() ?: 0
            val bt = b.groupValues[4].toIntOrNull() ?: 0
            n.w = r - l
            n.h = bt - t
        }
        val flags = inside.trim().split(' ').getOrNull(1) ?: ""
        n.clickable = flags.length > 6 && flags[6] == 'C'
        idRe.find(inside)?.let { n.vid = it.groupValues[1] }
        while (stack.size > 1 && stack.last().first >= indent) stack.removeLast()
        n.parent = stack.last().second
        stack.last().second.children.add(n)
        stack.addLast(indent to n)
        count++
    }
    val lin = root.children.first()
    println("parsed views=$count root=" + lin.cls)
    val tv = lin.children[0]
    println("TextView vid=$" + tv.vid + " clickable=$" + tv.clickable)

    // 2) 官方订阅开屏规则1（vid/结构类）在快层（text=null）的匹配
    val rule1 = "[childCount=0][visibleToUser=true][(text.length<10&&(text*=\"跳过\"||text*=\"skip\")) || id$=\"tt_splash_skip_btn\" || vid*=\"skip\" || (vid*=\"count\" && vid*=\"down\" && vid!*=\"download\") || desc*=\"跳过\"]"
    val sel1 = when (val r = Selector.compile(rule1)) {
        is SelectorCompileResult.Success -> r.value
        is SelectorCompileResult.Failure -> {
            println("compile fail: " + r.error.message)
            return
        }
    }
    val hit1 = A().querySelector(lin, sel1)
    println("rule1 fast-tier hit: " + (hit1 != null) + (if (hit1 != null) " -> " + hit1.cls + " vid=" + hit1.vid else ""))

    // 3) 对照：官方规则0（text 类）需要慢层才有的 text
    tv.text = "跳过 3"
    val rule0 = "[text*=\"跳过\"][text.length<10][visibleToUser=true]"
    val sel0 = (Selector.compile(rule0) as SelectorCompileResult.Success).value
    val hit0 = A().querySelector(lin, sel0)
    println("rule0 slow-tier hit: " + (hit0 != null) + (if (hit0 != null) " -> text=" + hit0.text else ""))
}
