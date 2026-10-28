package io.github.gkdhide.gkdx.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 通过 GitHub Releases 检查模块自身更新 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/suiren0219/GKDX/releases/latest"
    const val RELEASE_PAGE = "https://github.com/suiren0219/GKDX/releases/latest"

    data class Info(
        val tagName: String,
        val notes: String,
        val apkUrl: String?,
    )

    /** 拉取最新 Release；网络失败抛异常，由调用方提示 */
    suspend fun check(): Info = withContext(Dispatchers.IO) {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "GKDX")
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = JSONObject(text)
            var apkUrl: String? = null
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
            }
            Info(
                tagName = json.optString("tag_name"),
                notes = json.optString("body").take(600),
                apkUrl = apkUrl,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** v1.5.0 / 1.6.0 之类的版本号逐段比较 */
    fun isNewer(latest: String, current: String): Boolean {
        fun parse(v: String) = v.removePrefix("v").removePrefix("V")
            .split('.').map { seg ->
                seg.filter { it.isDigit() }.ifEmpty { "0" }.toIntOrNull() ?: 0
            }
        val a = parse(latest)
        val b = parse(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
