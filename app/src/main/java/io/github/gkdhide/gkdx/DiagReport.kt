package io.github.gkdhide.gkdx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build

/** 把 Root 模式全量诊断拼成一段可粘贴文本 */
object DiagReport {

    fun build(context: Context): String {
        val subs = try { SubscriptionStore.get(context) } catch (t: Throwable) { SafeLog.log(t); null }
        val sb = StringBuilder()
        sb.appendLine("== gkdX 诊断 ==")
        sb.appendLine("版本: " + BuildConfig.VERSION_NAME)
        sb.appendLine("设备: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.SDK_INT)
        sb.appendLine("root: " + (if (Diag.rootOk) "ok" else "失败 " + (RootShell.lastError ?: "")))
        sb.appendLine("订阅: " + (subs?.let { "${it.appCount} 应用 / ${it.ruleCount} 规则 / 全局组 ${it.globalGroups.size} / 排除 ${it.globalExcludedCount}" }
                ?: ("未加载 " + (SubscriptionStore.lastError ?: ""))))
        sb.appendLine("事件: ${Diag.events} 次（折叠 ${Diag.skipped}）· 日志行 ${Diag.logcatLines} · 焦点轮询 ${Diag.polls} 次")
        sb.appendLine("焦点: " + Diag.focusRaw.ifEmpty { "—" })
        sb.appendLine("快层(dumpsys): ${Diag.fastNodes} 节点 / ${Diag.fastMs}ms")
        sb.appendLine("慢层(uiautomator): ${Diag.lastDumpNodes} 节点 / ${Diag.lastDumpMs}ms · 失败 ${Diag.dumpFails}")
        sb.appendLine("最近命中: " + Diag.lastHit.ifEmpty { "—" })
        sb.appendLine("最近点击: " + Diag.lastTap.ifEmpty { "—" } + " " + Diag.agoText())
        if (Diag.lastError.isNotEmpty()) sb.appendLine("错误: " + Diag.lastError)
        sb.appendLine("---- 事件日志(最近40) ----")
        Diag.lines().takeLast(40).forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun copy(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("gkdX 诊断", text))
        true
    } catch (t: Throwable) {
        SafeLog.log(t)
        false
    }
}
