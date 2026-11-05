package io.github.gkdhide.gkdx

/**
 * 从 `dumpsys activity top` 的 View Hierarchy 段解析出节点树（快速读屏层）。
 *
 * 它比 uiautomator dump 快一个数量级（约 50-150ms），且能拿到：
 * 类名 / clickable / 坐标 / resource-id / 层级结构（childCount）；
 * 拿不到 text 和 desc —— 所以 vid/class/结构类规则可以直接命中，
 * text 类规则仍需退回 uiautomator 层。
 */
object DumpsysTree {

    private val lineRe = Regex("^([ |\\\\+`-]*)([a-zA-Z0-9_.$]+)\\{([^}]*)\\}")
    private val boundsRe = Regex("(-?\\d+),(-?\\d+)-(-?\\d+),(-?\\d+)")
    private val idRe = Regex("[a-zA-Z0-9_.]+:id/([A-Za-z0-9_.]+)")

    fun parse(output: String): XmlNode? {
        val started = output.indexOf("View Hierarchy:")
        if (started < 0) return null
        val root = XmlNode().apply {
            className = "root"
            visible = true
            left = 0; top = 0
            right = 1; bottom = 1
        }
        val stack = ArrayDeque<Pair<Int, XmlNode>>()
        stack.addLast(-1 to root)
        var any = false
        for (rawLine in output.substring(started).lines()) {
            val m = lineRe.find(rawLine) ?: continue
            val indent = m.groupValues[1].length
            val cls = m.groupValues[2]
            val inside = m.groupValues[3]

            val node = XmlNode()
            node.className = cls
            boundsRe.find(inside)?.let { b ->
                node.left = b.groupValues[1].toIntOrNull() ?: 0
                node.top = b.groupValues[2].toIntOrNull() ?: 0
                node.right = b.groupValues[3].toIntOrNull() ?: 0
                node.bottom = b.groupValues[4].toIntOrNull() ?: 0
            }
            // flags 组形如 "V.ED..C.."，C = clickable
            val flags = inside.trim().split(' ').getOrNull(1) ?: ""
            node.clickable = flags.contains('C')
            idRe.find(inside)?.let { node.resourceId = it.value }
            node.visible = node.width > 0 && node.height > 0

            while (stack.size > 1 && stack.last().first >= indent) stack.removeLast()
            node.parent = stack.last().second
            stack.last().second.children.add(node)
            node.depth = stack.last().second.depth + 1
            stack.addLast(indent to node)
            any = true
        }
        return if (any && root.children.isNotEmpty()) root.children.first() else root.takeIf { any }
    }
}
