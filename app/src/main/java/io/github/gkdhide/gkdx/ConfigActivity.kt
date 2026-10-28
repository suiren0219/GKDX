package io.github.gkdhide.gkdx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
 * 模块配置页：
 * - 订阅源管理（内置官方 + 3 个社区订阅）
 * - Root 模式开关（不注入应用进程，靠 root 读屏点击）
 * - 无障碍隐藏开关（配合 GKD 本体使用）
 * - 关于与原作者信息
 */
class ConfigActivity : Activity() {

    /** 内置订阅源：官方已停止维护，社区源提供更多/更新的规则；全部实测可用 */
    private val defaultUrls = listOf(
        "https://registry.npmmirror.com/@ganlinte/gkd-subscription/latest/files/dist/ganlin_gkd.json5",
        "https://registry.npmmirror.com/@aisouler/gkd_subscription/latest/files/dist/AIsouler_gkd.json5",
        "https://registry.npmmirror.com/@adpro/gkd_subscription/latest/files/dist/Adpro_gkd.json5",
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
    ).joinToString("\n")

    private lateinit var statusView: TextView
    private lateinit var rootStatusView: TextView
    private lateinit var urlsInput: EditText
    private lateinit var swHideMaster: Switch
    private lateinit var swStrict: Switch
    private lateinit var swHideInstalled: Switch
    private lateinit var swLog: Switch
    private lateinit var targetsInput: EditText
    private lateinit var excludesInput: EditText
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            rootStatusView.text = if (RootEngine.running) "Root 模式：运行中\n${RootEngine.status}" else "Root 模式：未运行"
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maybeRequestNotificationPermission()
        val sp = getSharedPreferences("config", Context.MODE_PRIVATE)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(title("GKD 订阅引擎", 22f))
        root.addView(desc(
            "LSPosed 模块，三种用法可自由组合：\n" +
                    "① Xposed 模式：作用域应用进程内直接匹配订阅规则（最快，需勾选作用域，不装无障碍）；\n" +
                    "② Root 模式：引擎跑在模块自己的 root 进程里，读屏点击，完全不注入任何应用；\n" +
                    "③ 无障碍隐藏：同时使用 GKD 本体时，对外隐藏其无障碍状态。\n\n" +
                    "Xposed 模式需要：LSPosed 启用模块 → 作用域勾选目标应用 → 强停重开。\n" +
                    "Root 模式需要：授予本应用 root 权限（与作用域无关）。"
        ))

        // ---------- 订阅 ----------
        root.addView(title("订阅", 16f))
        statusView = TextView(this).apply { text = loadStatus(sp) }
        root.addView(statusView)
        root.addView(label("订阅地址（每行一个 URL）："))
        urlsInput = input(sp.getString("urls", defaultUrls) ?: defaultUrls, 5)
        root.addView(urlsInput)
        root.addView(button("更新订阅") { update(sp) })

        // ---------- Root 模式 ----------
        root.addView(title("Root 模式（不勾作用域、不注入应用）", 16f))
        rootStatusView = TextView(this).apply { text = "Root 模式：未运行" }
        root.addView(rootStatusView)
        root.addView(desc("以 root 读屏（uiautomator dump）并点击（input tap），能覆盖 WebView 内部节点；轮询间隔约 0.5-1.2 秒，比 Xposed 模式慢。需要一个常驻通知。"))
        root.addView(button("启动 Root 模式") {
            if (!RootShell.isRoot()) {
                Toast.makeText(this, "未获得 root 权限，请先在 root 管理器中授予本应用", Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(this, RootEngineService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
            }
        })
        root.addView(button("停止 Root 模式") {
            stopService(Intent(this, RootEngineService::class.java))
        })

        // ---------- 无障碍隐藏 ----------
        root.addView(title("无障碍隐藏（配合 GKD 本体使用）", 16f))
        swHideMaster = addSwitch(root, "对外隐藏 GKD 的无障碍服务状态", sp.getBoolean("hide_master", true))
        swStrict = addSwitch(root, "完全伪装无障碍未开启（推荐）", sp.getBoolean("strict", true))
        swHideInstalled = addSwitch(root, "隐藏已安装服务列表（更彻底）", sp.getBoolean("hide_installed", false))
        swLog = addSwitch(root, "输出日志（LSPosed 日志过滤 GKDX）", sp.getBoolean("log", false))
        root.addView(label("额外隐藏目标（可选；li.songe.gkd 始终包含）："))
        targetsInput = input(sp.getString("targets", "") ?: "", 2)
        root.addView(targetsInput)
        root.addView(label("排除的应用（这些应用内不做隐藏）："))
        excludesInput = input(sp.getString("excludes", "") ?: "", 2)
        root.addView(excludesInput)
        root.addView(button("保存配置") { saveConfig(sp) })

        // ---------- 关于 ----------
        root.addView(title("关于 / 原作者", 16f))
        root.addView(desc(
            "• GKD 原作者：lisonge（GitHub @lisonge）\n" +
                    "  项目主页：https://github.com/gkd-kit/gkd\n" +
                    "  订阅数据：https://github.com/gkd-kit/subscription\n" +
                    "• 选择器引擎：取自 gkd-kit/gkd 的 gkd-selector 模块，原样内嵌于本模块，遵循 GPL-3.0\n" +
                    "• 内置社区订阅：#ganlinte、#AIsouler、#Adpro（各自作品，版权归原作者）\n" +
                    "• 无障碍隐藏思路参考：https://github.com/AoEiuV020/IAmNotDisabled\n" +
                    "• 本模块：https://github.com/suiren0219/GKDX ，同样以 GPL-3.0 发布\n\n" +
                    "本模块是第三方 LSPosed 实现，与 GKD 官方无关联；规则与引擎功劳属于 GKD 及其订阅作者们。"
        ))

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    // ---------- UI 辅助 ----------

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, (4 * resources.displayMetrics.density).toInt())
    }

    private fun desc(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (8 * resources.displayMetrics.density).toInt())
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
    }

    private fun addSwitch(root: LinearLayout, text: String, checked: Boolean): Switch {
        val sw = Switch(this).apply {
            this.text = text
            isChecked = checked
        }
        root.addView(sw)
        return sw
    }

    private fun input(text: String, lines: Int) = EditText(this).apply {
        setText(text)
        minLines = lines
        gravity = android.view.Gravity.TOP
        typeface = Typeface.MONOSPACE
        textSize = 11f
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
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
            val appIds = LinkedHashSet<String>()
            var ruleCount = 0
            val fail = mutableListOf<String>()
            for (url in urls) {
                try {
                    val text = download(url)
                    val root = Json5.parseToJsonElement(text).jsonObject
                    val name = (root["name"] as? JsonPrimitive)?.content ?: "未命名订阅"
                    val apps = (root["apps"] as? JsonArray) ?: throw IllegalArgumentException("缺少 apps 字段")
                    var rc = 0
                    for (appEl in apps) {
                        val app = appEl as? JsonObject ?: continue
                        (app["id"] as? JsonPrimitive)?.content?.let { appIds.add(it) }
                        val groups = (app["groups"] as? JsonArray) ?: continue
                        for (g in groups) {
                            rc += ((g as? JsonObject)?.get("rules") as? JsonArray)?.size ?: 0
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
                        // 作用域外应用可在进程启动时用它快速判断"有没有我的规则"，没有就一个 Hook 都不装
                        .putString("subs_apps", appIds.joinToString("|", prefix = "|", postfix = "|"))
                        .putString("subs_meta", "订阅: ${summaries.joinToString(" + ")} | 应用: ${appIds.size} | 规则: $ruleCount")
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
                        "更新完成：${appIds.size} 个应用 / $ruleCount 条规则" +
                                if (fail.isNotEmpty()) "（失败 ${fail.size} 个源）" else "",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(this, "全部订阅源下载失败，请检查网络", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun download(url: String): String {
        var current = URL(url)
        repeat(4) {
            val conn = current.openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "GKDX/1.2")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return@repeat
                    current = URL(current, location)
                    return@repeat
                }
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("重定向过多")
    }
}
