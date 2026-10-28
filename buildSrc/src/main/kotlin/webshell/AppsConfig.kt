package webshell

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.GradleException
import java.io.File
import java.net.URI

/**
 * apps.json 中的一个条目，对应一个独立的 App（一个 productFlavor、一个 APK）。
 *
 * 所有路径字段（icon / injectCss / injectJs）在加载时已解析为绝对路径并校验存在。
 */
data class SiteApp(
    /** 包名后缀，同时作为 flavor 名。最终包名 = com.yuanfeng.webshell.<id> */
    val id: String,
    /** 桌面 / 应用抽屉里显示的名字 */
    val name: String,
    /** 启动时打开的网址 */
    val url: String,
    /** 图标 PNG；为 null 时自动生成首字母图标 */
    val icon: File?,
    /** ARGB 颜色，用于状态栏、导航栏、启动页和自动图标底色 */
    val themeColor: Int,
    /** 这些域名（含其子域名）在 App 内打开，其余交给系统浏览器 */
    val allowedHosts: List<String>,
    /** 覆盖默认 User-Agent；可用 {default} 引用 WebView 默认值。null 表示不覆盖 */
    val userAgent: String?,
    val injectCss: File?,
    val injectJs: File?,
    val enablePullToRefresh: Boolean,
    val forceDarkMode: Boolean,
) {
    /** 用于 dist/ 文件名的安全版本名字（去掉 Windows / Unix 文件名非法字符和空白） */
    val fileSafeName: String
        get() = name.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_').ifEmpty { id }

    /** flavor 名首字母大写，用于拼接 Gradle 任务名（如 assembleWikipediaRelease） */
    val capitalizedId: String
        get() = id.replaceFirstChar { it.uppercaseChar() }

    /** 启动 URL 是否为 http://（明文）。是的话该 App 需要允许明文流量 */
    val usesCleartext: Boolean
        get() = url.startsWith("http://", ignoreCase = true)

    /**
     * 写进 Manifest android:label 的字面值。AAPT2 处理属性字符串时把 \ ' " 当转义 / 引号字符，
     * 开头的 @ ? 会被当成资源引用，都需要转义。
     */
    val manifestLabel: String
        get() {
            val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
            return if (escaped.startsWith("@") || escaped.startsWith("?")) "\\$escaped" else escaped
        }
}

/**
 * 读取并校验 apps.json。
 *
 * 设计原则：Fail Fast。任何字段写错（拼错键名、颜色格式不对、文件不存在、id 重复……）
 * 都在 Gradle 配置阶段直接报错，并指出是哪个条目的哪个字段，而不是等到运行时才发现。
 */
object AppsConfig {

    /**
     * 「模板」App：不对应任何网站，构建出的 APK 被打包进工厂 App，
     * 工厂在手机上改写它的包名、名称、图标和 config.json 来生成新 App。
     * 包名必须带一个代码包名里不会出现的后缀（.template），工厂按这个前缀替换 Manifest 里的包名相关字符串。
     */
    val TEMPLATE = SiteApp(
        id = "template",
        name = "WebShell Template",
        url = "https://example.com/",
        icon = null,
        themeColor = 0xFFFFFFFF.toInt(),
        allowedHosts = listOf("example.com"),
        userAgent = null,
        injectCss = null,
        injectJs = null,
        enablePullToRefresh = false,
        forceDarkMode = false,
    )

    private val KNOWN_KEYS = setOf(
        "id", "name", "url", "icon", "themeColor", "allowedHosts", "userAgent",
        "injectCss", "injectJs", "enablePullToRefresh", "forceDarkMode",
    )

    /** id 必须是合法的 Java 包名片段，也必须是合法的 flavor 名 */
    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]*$")

    /** Java 关键字不能出现在包名里；这些名字也会和 Gradle 内置的 source set / build type 冲突 */
    private val RESERVED_IDS = setOf(
        // Gradle / AGP 保留
        "main", "test", "debug", "release", "androidtest",
        // 本工程保留：模板 App 和工厂 App
        "template", "factory",
        // Java 关键字和字面量
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null",
    )

    fun load(configFile: File): List<SiteApp> {
        if (!configFile.isFile) fail("找不到配置文件：${configFile.absolutePath}")
        val baseDir = configFile.parentFile

        val root = try {
            JsonParser.parseString(configFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            fail("apps.json 不是合法的 JSON：${e.message}")
        }
        if (!root.isJsonObject || !root.asJsonObject.has("apps") || !root.asJsonObject["apps"].isJsonArray) {
            fail("apps.json 顶层必须是 { \"apps\": [ ... ] }")
        }
        val entries = root.asJsonObject["apps"].asJsonArray
        if (entries.isEmpty) fail("apps.json 里至少要有一个 App")

        val apps = entries.mapIndexed { index, element ->
            if (!element.isJsonObject) fail("apps[$index] 必须是一个对象")
            parseEntry(element.asJsonObject, "apps[$index]", baseDir)
        }

        apps.groupBy { it.id }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            fail("id 重复：\"$it\"。每个 App 的 id 必须唯一（它决定包名）")
        }
        apps.groupBy { it.fileSafeName }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            fail("name 重复：\"$it\"。dist/ 输出文件按 name 命名，必须唯一")
        }
        return apps
    }

    private fun parseEntry(obj: JsonObject, where: String, baseDir: File): SiteApp {
        // 键名拼错（例如 themecolor）是最常见的配置错误，直接报出来。以 _ 开头的键视为注释，允许存在
        obj.keySet().filter { it !in KNOWN_KEYS && !it.startsWith("_") }.let { unknown ->
            if (unknown.isNotEmpty()) fail("$where 含未知字段 $unknown；允许的字段：$KNOWN_KEYS")
        }

        val id = obj.requiredString("id", where)
        val at = "$where (id=$id)"
        if (!ID_PATTERN.matches(id)) fail("$at: id 只能包含小写字母、数字和下划线，且以字母开头")
        if (id.lowercase() in RESERVED_IDS || id.startsWith("test")) fail("$at: id \"$id\" 是保留字，换一个")

        val name = obj.requiredString("name", at)

        val url = obj.requiredString("url", at)
        val uri = try { URI(url) } catch (e: Exception) { fail("$at: url 无法解析：${e.message}") }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            fail("$at: url 必须是 http:// 或 https:// 开头的完整网址")
        }

        val themeColor = parseColor(obj.optionalString("themeColor", at) ?: "#FFFFFF", at)

        val allowedHosts = (obj.optionalStringList("allowedHosts", at) ?: emptyList())
            .map { normalizeHost(it, at) }
            .ifEmpty { listOf(uri.host.lowercase()) }
        if (allowedHosts.none { uri.host.lowercase() == it || uri.host.lowercase().endsWith(".$it") }) {
            fail("$at: url 的域名 ${uri.host} 不在 allowedHosts $allowedHosts 内，启动页会被当成外链")
        }

        return SiteApp(
            id = id,
            name = name,
            url = url,
            icon = obj.optionalFile("icon", at, baseDir, ".png"),
            themeColor = themeColor,
            allowedHosts = allowedHosts.distinct(),
            userAgent = obj.optionalString("userAgent", at)?.takeIf { it.isNotBlank() },
            injectCss = obj.optionalFile("injectCss", at, baseDir, ".css"),
            injectJs = obj.optionalFile("injectJs", at, baseDir, ".js"),
            enablePullToRefresh = obj.optionalBoolean("enablePullToRefresh", at) ?: false,
            forceDarkMode = obj.optionalBoolean("forceDarkMode", at) ?: false,
        )
    }

    /** 允许写 "example.com"、"*.example.com"、".example.com"，统一成 "example.com"（子域名自动包含） */
    private fun normalizeHost(raw: String, at: String): String {
        val host = raw.trim().lowercase().removePrefix("*.").removePrefix(".")
        if (host.isEmpty() || host.contains('/') || host.contains(':') || host.contains('*')) {
            fail("$at: allowedHosts 里的 \"$raw\" 不是合法域名（只写域名，不要写协议、端口或路径）")
        }
        return host
    }

    /** 支持 #RRGGBB 和 #AARRGGBB，返回 ARGB Int */
    private fun parseColor(raw: String, at: String): Int {
        val hex = raw.removePrefix("#")
        val value = hex.toLongOrNull(16)
        return when {
            value == null -> fail("$at: themeColor \"$raw\" 不是合法的十六进制颜色")
            hex.length == 6 -> (0xFF000000L or value).toInt()
            hex.length == 8 -> value.toInt()
            else -> fail("$at: themeColor 必须是 #RRGGBB 或 #AARRGGBB，实际是 \"$raw\"")
        }
    }

    // ---- JSON 取值辅助：类型不对时给出明确报错 ----

    private fun JsonObject.requiredString(key: String, at: String): String =
        optionalString(key, at)?.takeIf { it.isNotBlank() } ?: fail("$at: 缺少必填字段 \"$key\"")

    private fun JsonObject.optionalString(key: String, at: String): String? {
        val e: JsonElement = get(key) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isString) fail("$at: \"$key\" 必须是字符串")
        return e.asString.trim()
    }

    private fun JsonObject.optionalBoolean(key: String, at: String): Boolean? {
        val e: JsonElement = get(key) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) fail("$at: \"$key\" 必须是 true 或 false")
        return e.asBoolean
    }

    private fun JsonObject.optionalStringList(key: String, at: String): List<String>? {
        val e: JsonElement = get(key) ?: return null
        if (e.isJsonNull) return null
        if (!e.isJsonArray) fail("$at: \"$key\" 必须是字符串数组")
        return e.asJsonArray.map {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isString) fail("$at: \"$key\" 的元素必须是字符串")
            it.asString
        }
    }

    private fun JsonObject.optionalFile(key: String, at: String, baseDir: File, ext: String): File? {
        val path = optionalString(key, at)?.takeIf { it.isNotBlank() } ?: return null
        val file = File(path).let { if (it.isAbsolute) it else File(baseDir, path) }
        if (!file.isFile) fail("$at: \"$key\" 指向的文件不存在：${file.absolutePath}")
        if (!file.name.endsWith(ext, ignoreCase = true)) fail("$at: \"$key\" 应该是 $ext 文件，实际是 ${file.name}")
        return file
    }

    private fun fail(message: String): Nothing = throw GradleException("[apps.json] $message")
}
