package io.github.gkdhide.gkdx

import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.EditText
import android.widget.TextView
import li.gkd.selector.NodeAdapter

/**
 * 把目标应用进程内的 android.view.View 树适配成 GKD 选择器引擎的节点抽象。
 * 属性集合与 GKD 无障碍适配器(li.gkd.app.a11y.A11yContext)对齐：
 * getName 返回完整类名，引擎的名称匹配是"结尾等于且前一位为 . "，
 * 因此订阅里的短类名(@ImageView)与全名(@android.widget.ImageView)都能命中。
 */
class ViewNodeAdapter : NodeAdapter<View>() {

    override fun getName(node: View): String = node.javaClass.name

    override fun getChildCount(node: View): Int = (node as? ViewGroup)?.childCount ?: 0

    override fun getChild(node: View, index: Int): View? = (node as? ViewGroup)?.getChildAt(index)

    override fun getParent(node: View): View? = node.parent as? View

    override fun getNodeKey(node: View): Any = node

    override fun getAttr(target: Any, name: String): Any? {
        val v = target as? View ?: return null
        return when (name) {
            "id" -> fullId(v)
            "vid" -> vid(v)
            "name" -> v.javaClass.name
            "text" -> (v as? TextView)?.text?.toString()
            "desc" -> v.contentDescription?.toString()
            "clickable" -> v.isClickable
            "longClickable" -> v.isLongClickable
            "focusable" -> v.isFocusable
            "scrollable" -> false
            "checkable" -> v is Checkable
            "checked" -> (v as? Checkable)?.isChecked ?: false
            "selected" -> v.isSelected
            "enabled" -> v.isEnabled
            "editable" -> v is EditText
            "visibleToUser" -> v.isShown && v.windowVisibility == View.VISIBLE
                    && v.width > 0 && v.height > 0
            "childCount" -> (v as? ViewGroup)?.childCount ?: 0
            "index" -> (v.parent as? ViewGroup)?.indexOfChild(v) ?: 0
            "depth" -> depthOf(v)
            "left", "top", "right", "bottom", "width", "height", "centerX", "centerY" ->
                boundsAttr(v, name)
            else -> null
        }
    }

    private fun fullId(v: View): String? =
        if (v.id == View.NO_ID) null
        else runCatching { v.resources.getResourceName(v.id) }.getOrNull()

    private fun vid(v: View): String? =
        if (v.id == View.NO_ID) null
        else runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull()

    private fun depthOf(v: View): Int {
        var d = 0
        var p = v.parent
        while (p is View) {
            d++
            p = p.parent
        }
        return d
    }

    private fun boundsAttr(v: View, name: String): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return when (name) {
            "left" -> loc[0]
            "top" -> loc[1]
            "right" -> loc[0] + v.width
            "bottom" -> loc[1] + v.height
            "width" -> v.width
            "height" -> v.height
            "centerX" -> loc[0] + v.width / 2
            "centerY" -> loc[1] + v.height / 2
            else -> 0
        }
    }
}
