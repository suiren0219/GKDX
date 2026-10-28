package io.github.gkdhide.gkdx

import li.gkd.selector.NodeAdapter
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** uiautomator dump 得到的轻量节点 */
class XmlNode {
    var className: String = ""
    var text: String = ""
    var desc: String = ""
    var resourceId: String = ""
    var clickable: Boolean = false
    var visible: Boolean = true
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    var parent: XmlNode? = null
    val children: ArrayList<XmlNode> = ArrayList()
    var depth: Int = 0

    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val vid: String get() = resourceId.substringAfterLast('/')
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 把 uiautomator 的 XML 树适配成 GKD 选择器引擎的节点接口。
 * 与无障碍/View 适配器保持同一套属性语义；uiautomator 树能看到 WebView 内部节点。
 */
class XmlNodeAdapter : NodeAdapter<XmlNode>() {

    override fun getName(node: XmlNode): String = node.className

    override fun getChildCount(node: XmlNode): Int = node.children.size

    override fun getChild(node: XmlNode, index: Int): XmlNode? = node.children.getOrNull(index)

    override fun getParent(node: XmlNode): XmlNode? = node.parent

    override fun getNodeKey(node: XmlNode): Any = node

    override fun getAttr(target: Any, name: String): Any? {
        val n = target as? XmlNode ?: return null
        return when (name) {
            "id" -> n.resourceId.ifEmpty { null }
            "vid" -> n.vid.ifEmpty { null }
            "name" -> n.className
            "text" -> n.text.ifEmpty { null }
            "desc" -> n.desc.ifEmpty { null }
            "clickable" -> n.clickable
            "visibleToUser" -> n.visible && n.width > 0 && n.height > 0
            "longClickable" -> false
            "focusable" -> true
            "checkable" -> false
            "checked" -> false
            "editable" -> n.className.contains("EditText")
            "selected" -> false
            "childCount" -> n.children.size
            "index" -> n.parent?.children?.indexOf(n) ?: 0
            "depth" -> n.depth
            "left" -> n.left
            "top" -> n.top
            "right" -> n.right
            "bottom" -> n.bottom
            "width" -> n.width
            "height" -> n.height
            "centerX" -> n.centerX
            "centerY" -> n.centerY
            else -> null
        }
    }

    companion object {
        private val boundsRegex = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

        /** 解析 uiautomator dump 的 XML，返回根节点 */
        fun parse(xml: String): XmlNode? {
            if (xml.isBlank() || !xml.contains("<hierarchy")) return null
            return try {
                val factory = DocumentBuilderFactory.newInstance()
                factory.isNamespaceAware = false
                // 关闭外部实体，避免畸形 XML 引发解析器问题
                runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                val doc = factory.newDocumentBuilder()
                    .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                val rootEl = doc.documentElement ?: return null
                val firstNode = firstElementChild(rootEl) ?: return null
                build(firstNode, null, 0)
            } catch (t: Throwable) {
                null
            }
        }

        private fun firstElementChild(el: Element): Element? {
            var child: Node? = el.firstChild
            while (child != null) {
                if (child is Element) return child
                child = child.nextSibling
            }
            return null
        }

        private fun build(el: Element, parent: XmlNode?, depth: Int): XmlNode {
            val node = XmlNode()
            node.className = el.getAttribute("class") ?: ""
            node.text = el.getAttribute("text") ?: ""
            node.desc = el.getAttribute("content-desc") ?: ""
            node.resourceId = el.getAttribute("resource-id") ?: ""
            node.clickable = el.getAttribute("clickable") == "true"
            node.visible = el.getAttribute("visible-to-user") != "false"
            boundsRegex.find(el.getAttribute("bounds") ?: "")?.let { m ->
                node.left = m.groupValues[1].toIntOrNull() ?: 0
                node.top = m.groupValues[2].toIntOrNull() ?: 0
                node.right = m.groupValues[3].toIntOrNull() ?: 0
                node.bottom = m.groupValues[4].toIntOrNull() ?: 0
            }
            node.parent = parent
            node.depth = depth
            var child: Node? = el.firstChild
            while (child != null) {
                if (child is Element && child.tagName == "node") {
                    node.children.add(build(child, node, depth + 1))
                }
                child = child.nextSibling
            }
            return node
        }
    }
}
