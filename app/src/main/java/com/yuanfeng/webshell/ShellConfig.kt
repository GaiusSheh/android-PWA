package com.yuanfeng.webshell

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import org.json.JSONObject

/**
 * 当前 App 的配置，运行时从 assets/webshell/config.json 读取。
 *
 * 为什么不用 BuildConfig 常量：同一个「模板 APK」既要被 PC 端 Gradle 按 apps.json 批量构建，
 * 也要被手机上的「工厂 App」直接改写生成新 App。工厂没有编译器，只能替换 APK 里的文件，
 * 所以每个 App 的差异必须放在可替换的文件里（config.json、图标 PNG），而不是编译进代码。
 *
 * config.json 由 PC 端 GenerateSiteResourcesTask 或工厂的 ApkGenerator 生成，格式：
 * {
 *   "url": "https://example.com/",
 *   "allowedHosts": ["example.com"],
 *   "themeColor": "#FF2196F3",
 *   "userAgent": "",
 *   "enablePullToRefresh": true,
 *   "forceDarkMode": false
 * }
 */
object ShellConfig {
    const val ASSET_PATH = "webshell/config.json"

    lateinit var startUrl: String
        private set
    lateinit var allowedHosts: List<String>
        private set
    var themeColor: Int = 0xFFFFFFFF.toInt()
        private set
    var pullToRefresh: Boolean = false
        private set
    var forceDarkMode: Boolean = false
        private set
    private var userAgentTemplate: String = ""

    @Volatile
    private var loaded = false

    /** 在 MainActivity.onCreate 的最开始调用；重复调用无副作用 */
    fun init(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val json = context.assets.open(ASSET_PATH).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            startUrl = json.getString("url")
            allowedHosts = json.optJSONArray("allowedHosts")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it).lowercase() } }
                ?.ifEmpty { null }
                ?: listOfNotNull(Uri.parse(startUrl).host?.lowercase())
            themeColor = parseColor(json.optString("themeColor", "#FFFFFFFF"))
            userAgentTemplate = json.optString("userAgent", "")
            pullToRefresh = json.optBoolean("enablePullToRefresh", false)
            forceDarkMode = json.optBoolean("forceDarkMode", false)
            loaded = true
        }
    }

    /**
     * 实际使用的 User-Agent；null 表示用 WebView 默认值。
     * 配置里可以写 {default} 引用默认 UA，例如 "{default} MyShell/1.0"。
     */
    fun userAgent(context: Context): String? {
        val configured = userAgentTemplate.ifEmpty { return null }
        return configured.replace("{default}", WebSettings.getDefaultUserAgent(context))
    }

    /** 该地址是否应在 App 内打开：http(s) 且域名等于或属于 allowedHosts 中某一项的子域名 */
    fun isAllowed(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return allowedHosts.any { host == it || host.endsWith(".$it") }
    }

    fun isAllowed(url: String?): Boolean = url != null && isAllowed(Uri.parse(url))

    /** 支持 #RRGGBB / #AARRGGBB */
    private fun parseColor(s: String): Int {
        val hex = s.removePrefix("#")
        val v = hex.toLong(16)
        return if (hex.length == 6) (0xFF000000L or v).toInt() else v.toInt()
    }
}
