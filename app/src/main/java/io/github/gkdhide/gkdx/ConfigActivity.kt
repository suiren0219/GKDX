package io.github.gkdhide.gkdx

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import li.songe.json5.Json5
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 模块配置页（两种能力合一）：
 * - 订阅引擎：管理订阅源、下载订阅（gzip+base64 存入 SharedPreferences 供 Hook 侧读取）
 * - 无障碍隐藏：开关与目标配置
 */
class ConfigActivity : Activity() {

    private val defaultUrl =
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"

    private lateinit var statusView: TextView
    private lateinit var urlsInput: EditText
    private lateinit var swHideMaster: Switch
    private lateinit var swStrict: Switch
    private lateinit var swHideInstalled: Switch
    private lateinit var swLog: Switch
    private lateinit var targetsInput: EditText
    private lateinit var excludesInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("config", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(sectionTitle("GKD 订阅引擎", 22f, true))
        root.addView(descText(
            "LSPosed 模块（订阅引擎 + 无障碍隐藏 二合一）：\n" +
                    "① 在作用域应用进程内直接运行 GKD 订阅规则跳广告，无需无障碍权限；\n" +
                    "② 若你同时使用 GKD 本体，可对外隐藏其无障碍状态帮助过检测。\n\n" +
                    "使用：1. LSPosed 启用本模块；2. 作用域勾选目标应用；3. 强停目标应用重开；4. 点\"更新订阅\"拉规则。"
        ))
        statusView = TextView(this).apply { text = loadStatus(sp) }
        root.addView(statusView)

        root.addView(sectionTitle("订阅地址（每行一个 URL）", 14f, false))
        urlsInput = input(sp.getString("urls", defaultUrl) ?: "", 3)
        root.addView(urlsInput)
        root.addView(button("更新订阅") { update(sp) })

        root.addView(sectionTitle("隐藏 GKD 无障碍（配合 GKD 本体使用）", 16f, true))
        swHideMaster = switch("对外隐藏 GKD 的无障碍服务状态（不用 GKD 可关闭）", sp.getBoolean("hide_master", true))
        root.addView(swHideMaster)
        swStrict = switch("完全伪装无障碍未开启（推荐；目标应用认为无障碍整体关闭）", sp.getBoolean("strict", true))
        root.addView(swStrict)
        swHideInstalled = switch("隐藏已安装服务列表（更彻底，可能影响目标应用自身无障碍功能）", sp.getBoolean("hide_installed", false))
        root.addView(swHideInstalled)
        swLog = switch("输出日志（LSPosed 日志中查看 GKDX 前缀）", sp.getBoolean("log", false))
        root.addView(swLog)

        root.addView(sectionTitle("额外隐藏目标（可选；li.songe.gkd 始终包含）", 14f, false))
        targetsInput = input(sp.getString("targets", "") ?: "", 2)
        root.addView(targetsInput)
        root.addView(sectionTitle("排除的应用（这些应用内不做隐藏）", 14f, false))
        excludesInput = input(sp.getString("excludes", "") ?: "", 2)
        root.addView(excludesInput)

        root.addView(button("保存配置") { saveConfig(sp) })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    // ---------- UI 辅助 ----------

    private fun sectionTitle(text: String, size: Float, bold: Boolean) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
    }

    private fun descText(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (10 * resources.displayMetrics.density).toInt())
    }

    private fun switch(text: String, checked: Boolean) = Switch(this).apply {
        this.text = text
        this.isChecked = checked
    }

    private fun input(text: String, lines: Int) = EditText(this).apply {
        setText(text)
        minLines = lines
        gravity = android.view.Gravity.TOP
        typeface = Typeface.MONOSPACE
        textSize = 12f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (12 * resources.displayMetrics.density).toInt() }
    }

    // ---------- 状态与动作 ----------

    private fun loadStatus(sp: SharedPreferences): String {
        val last = sp.getLong("last_update", 0L)
        val meta = sp.getString("subs_meta", "") ?: ""
        return if (last == 0L) "状态：尚未下载订阅"
        else "状态：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last))}\n$meta"
    }

    private fun saveConfig(sp: SharedPreferences) {
        sp.edit()
            .putString("urls", urlsInput.text.toString())
            .putBoolean("hide_master", swHideMaster.isChecked)
            .putBoolean("strict", swStrict.isChecked)
            .putBoolean("hide_installed", swHideInstalled.isChecked)
            .putBoolean("log", swLog.isChecked)
            .putString("targets", targetsInput.text.toString())
            .putString("excludes", excludesInput.text.toString())
            .apply()
        Toast.makeText(this, "已保存，配置约 3 秒内热生效；若未生效请强停目标应用重开", Toast.LENGTH_LONG).show()
    }

    private fun update(sp: SharedPreferences) {
        val urls = urlsInput.text.toString().split("\n", ",", ";")
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (urls.isEmpty()) {
            Toast.makeText(this, "请至少填写一个订阅地址", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "开始更新…", Toast.LENGTH_SHORT).show()
        Thread {
            val store = JSONArray()
            val summaries = mutableListOf<String>()
            var ruleCount = 0
            var appCount = 0
            val fail = mutableListOf<String>()
            for (url in urls) {
                try {
                    val text = download(url)
                    // 解析校验 + 统计（与 Hook 侧同一套 json5 解析）
                    val root = Json5.parseToJsonElement(text).jsonObject
                    val name = (root["name"] as? JsonPrimitive)?.content ?: "未命名订阅"
                    val apps = (root["apps"] as? JsonArray)
                        ?: throw IllegalArgumentException("缺少 apps 字段")
                    var rc = 0
                    for (appEl in apps) {
                        val app = appEl as? JsonObject ?: continue
                        appCount++
                        val groups = (app["groups"] as? JsonArray) ?: continue
                        for (g in groups) {
                            val rules = ((g as? JsonObject)?.get("rules") as? JsonArray) ?: continue
                            rc += rules.size
                        }
                    }
                    summaries += name
                    ruleCount += rc
                    store.put(
                        JSONObject().put("url", url)
                            .put("data", android.util.Base64.encodeToString(SubscriptionStore.gzip(text), android.util.Base64.NO_WRAP))
                    )
                } catch (t: Throwable) {
                    fail += url
                }
            }
            runOnUiThread {
                if (store.length() > 0) {
                    sp.edit()
                        .putString("subs_store", store.toString())
                        .putString("subs_meta", "订阅: ${summaries.joinToString(" + ")} | 应用: $appCount | 规则: $ruleCount")
                        .putLong("last_update", System.currentTimeMillis())
                        .putString("urls", urlsInput.text.toString())
                        .putBoolean("hide_master", swHideMaster.isChecked)
                        .putBoolean("strict", swStrict.isChecked)
                        .putBoolean("hide_installed", swHideInstalled.isChecked)
                        .putBoolean("log", swLog.isChecked)
                        .putString("targets", targetsInput.text.toString())
                        .putString("excludes", excludesInput.text.toString())
                        .apply()
                    statusView.text = loadStatus(sp)
                    Toast.makeText(
                        this,
                        "更新完成：$ruleCount 条规则" + if (fail.isNotEmpty()) "（失败 ${fail.size} 个源）" else "",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "全部订阅源下载失败，请检查网络", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", "GKDX/1.1")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
