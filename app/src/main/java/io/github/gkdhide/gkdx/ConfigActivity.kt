package io.github.gkdhide.gkdx

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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

/** 模块配置页：管理订阅源、下载订阅（gzip+base64 存入 SharedPreferences 供 Hook 侧读取） */
class ConfigActivity : Activity() {

    private val defaultUrl =
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5"

    private lateinit var statusView: TextView
    private lateinit var urlsInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sp = getSharedPreferences("config", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "GKD 订阅引擎"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "LSPosed 模块：在作用域应用进程内直接运行 GKD 订阅规则（官方 selector 引擎），跳过开屏广告/弹窗，无需无障碍权限。\n\n" +
                    "使用：\n1. LSPosed 中启用本模块；\n2. 作用域勾选需要跳广告的应用；\n3. 强制停止目标应用后重开；\n4. 点下方\"更新订阅\"拉取规则。\n\n" +
                    "提示：默认订阅已停止维护，可自行添加社区订阅地址（每行一个 URL）。"
            setPadding(0, (10 * d).toInt(), 0, (10 * d).toInt())
        })

        statusView = TextView(this).apply { text = loadStatus(sp) }
        root.addView(statusView)

        root.addView(TextView(this).apply {
            text = "订阅地址（每行一个 URL）："
            setPadding(0, (12 * d).toInt(), 0, (4 * d).toInt())
        })
        urlsInput = EditText(this).apply {
            setText(sp.getString("urls", defaultUrl))
            minLines = 3
            gravity = android.view.Gravity.TOP
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }
        root.addView(urlsInput)

        root.addView(Button(this).apply {
            text = "更新订阅"
            setOnClickListener { update(sp) }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (12 * d).toInt() })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun loadStatus(sp: android.content.SharedPreferences): String {
        val last = sp.getLong("last_update", 0L)
        val meta = sp.getString("subs_meta", "") ?: ""
        return if (last == 0L) "状态：尚未下载订阅"
        else "状态：${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(last))}\n$meta"
    }

    private fun update(sp: android.content.SharedPreferences) {
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
            var fail = mutableListOf<String>()
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
                    summaries += "$name"
                    ruleCount += rc
                    store.put(JSONObject().put("url", url).put("data", android.util.Base64.encodeToString(SubscriptionStore.gzip(text), android.util.Base64.NO_WRAP)))
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
        conn.setRequestProperty("User-Agent", "GKDX/1.0")
        try {
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }
}
