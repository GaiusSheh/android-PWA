package com.yuanfeng.webshell.factory.core

import java.net.URI

/**
 * 与网址相关的纯逻辑规则：包名后缀、默认白名单、CSS 颜色解析、config.json 生成。
 * 不依赖 Android API，可以在电脑上做单元测试。
 */
object SiteRules {

    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]*$")

    /** 不能作为包名片段（Java 关键字）或与本工程保留名冲突的 id，与 buildSrc/AppsConfig.kt 保持一致 */
    private val RESERVED_IDS = setOf(
        "main", "test", "debug", "release", "androidtest", "template", "factory",
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null",
    )

    /** 常见的「二级后缀」：example.com.cn 的主域名是 example，而不是 com */
    private val SECOND_LEVEL = setOf("com", "net", "org", "gov", "edu", "co", "ac", "or", "ne", "go")

    fun isValidId(id: String): Boolean =
        ID_PATTERN.matches(id) && id !in RESERVED_IDS && !id.startsWith("test")

    /** 为什么 id 不合法；合法时返回 null */
    fun idProblem(id: String): String? = when {
        id.isEmpty() -> "不能为空"
        !ID_PATTERN.matches(id) -> "只能包含小写字母、数字和下划线，且以字母开头"
        id in RESERVED_IDS || id.startsWith("test") -> "是保留字，换一个"
        else -> null
    }

    /** 规范化用户输入的网址：补全 https://，去掉首尾空白 */
    fun normalizeUrl(input: String): String? {
        var s = input.trim()
        if (s.isEmpty()) return null
        if (!s.contains("://")) s = "https://$s"
        return try {
            val uri = URI(s)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) s else null
        } catch (e: Exception) {
            null
        }
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removeSuffix(".")
    } catch (e: Exception) {
        null
    }

    /**
     * 主域名，用作默认白名单：news.ycombinator.com → ycombinator.com，www.bbc.co.uk → bbc.co.uk。
     * 白名单匹配时自动包含子域名，所以写主域名即可覆盖 m.、www.、login. 等子站。
     * IP 地址和 localhost 原样返回。
     */
    fun baseDomain(host: String): String {
        val h = host.lowercase()
        if (h.matches(Regex("""^[\d.]+$""")) || h.contains(':') || !h.contains('.')) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val tld = labels[labels.size - 1]
        val second = labels[labels.size - 2]
        val keep = if (tld.length == 2 && second in SECOND_LEVEL) 3 else 2
        return labels.takeLast(keep).joinToString(".")
    }

    /**
     * 根据网址推荐包名后缀：news.ycombinator.com → news_ycombinator，www.bilibili.com → bilibili。
     * 去掉 www. / m. 前缀和顶级域名，剩余部分用下划线连接；与已有 id 冲突时追加数字。
     */
    fun suggestId(host: String, existing: Set<String>): String {
        val labels = host.lowercase().split('.').filter { it.isNotEmpty() }.toMutableList()
        while (labels.size > 1 && labels.first() in setOf("www", "m", "mobile", "wap")) labels.removeAt(0)
        // 去掉顶级域名（以及 com.cn 这类二级后缀）
        if (labels.size >= 2) {
            val tld = labels.removeAt(labels.size - 1)
            if (tld.length == 2 && labels.size >= 2 && labels.last() in SECOND_LEVEL) labels.removeAt(labels.size - 1)
        }
        var base = labels.joinToString("_").replace(Regex("[^a-z0-9_]"), "_").replace(Regex("_+"), "_").trim('_')
        if (base.isEmpty()) base = "site"
        if (!base[0].isLetter()) base = "s$base"
        if (!isValidId(base)) base = "${base}_app"
        var candidate = base
        var n = 2
        while (candidate in existing) candidate = "${base}_${n++}"
        return candidate
    }

    /**
     * 解析网页里的 CSS 颜色（<meta name="theme-color">、manifest 的 theme_color），返回 ARGB。
     * 支持 #rgb、#rgba、#rrggbb、#rrggbbaa、rgb()、rgba() 和少量颜色名；无法识别时返回 null。
     * 注意 CSS 的 #rrggbbaa 透明度在最后，Android 的 #AARRGGBB 在最前。
     */
    fun parseCssColor(raw: String?): Int? {
        val s = raw?.trim()?.lowercase() ?: return null
        if (s.startsWith("#")) {
            val hex = s.drop(1)
            if (!hex.all { it in "0123456789abcdef" }) return null
            val full = when (hex.length) {
                3, 4 -> hex.map { "$it$it" }.joinToString("")
                6, 8 -> hex
                else -> return null
            }
            val rgb = full.substring(0, 6).toLong(16).toInt()
            val alpha = if (full.length == 8) full.substring(6, 8).toInt(16) else 0xFF
            return (alpha shl 24) or rgb
        }
        Regex("""^rgba?\(\s*(\d+)[,\s]+(\d+)[,\s]+(\d+)(?:\s*[,/]\s*([\d.]+%?))?\s*\)$""").matchEntire(s)?.let { m ->
            val (r, g, b) = (1..3).map { m.groupValues[it].toInt().coerceIn(0, 255) }
            val a = m.groupValues[4].takeIf { it.isNotEmpty() }?.let {
                if (it.endsWith("%")) it.dropLast(1).toDouble() / 100 else it.toDouble()
            } ?: 1.0
            return ((a.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        return NAMED_COLORS[s]
    }

    private val NAMED_COLORS = mapOf(
        "white" to 0xFFFFFFFF.toInt(), "black" to 0xFF000000.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF008000.toInt(), "blue" to 0xFF0000FF.toInt(), "orange" to 0xFFFFA500.toInt(),
        "gray" to 0xFF808080.toInt(), "grey" to 0xFF808080.toInt(),
    )

    /** 生成壳读取的 assets/webshell/config.json（字段含义见壳的 ShellConfig.kt） */
    fun configJson(
        url: String,
        allowedHosts: List<String>,
        themeColor: Int,
        userAgent: String,
        enablePullToRefresh: Boolean,
        forceDarkMode: Boolean,
    ): String = buildString {
        append("{\n")
        append("  \"url\": ").append(quote(url)).append(",\n")
        append("  \"allowedHosts\": [").append(allowedHosts.joinToString(", ") { quote(it) }).append("],\n")
        append("  \"themeColor\": ").append(quote("#%08X".format(themeColor))).append(",\n")
        append("  \"userAgent\": ").append(quote(userAgent)).append(",\n")
        append("  \"enablePullToRefresh\": ").append(enablePullToRefresh).append(",\n")
        append("  \"forceDarkMode\": ").append(forceDarkMode).append("\n")
        append("}\n")
    }

    /** JSON 字符串转义 */
    fun quote(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        append('"')
    }

    /** 人机验证 / 反爬页面的常见标题（Cloudflare、DDoS-Guard 等，含中文本地化版本），小写比较 */
    private val CHALLENGE_TITLES = listOf(
        "just a moment", "attention required", "checking your browser", "please wait",
        "one more step", "verifying you are human", "ddos-guard", "请稍候", "请稍等", "安全验证",
    )

    fun isChallengeTitle(title: String?): Boolean {
        val t = title?.trim()?.lowercase() ?: return false
        return CHALLENGE_TITLES.any { t.startsWith(it) || t.contains("| $it") }
    }

    /**
     * 拿到的是不是人机验证页（而不是网站本身）。
     * Cloudflare 的 JS 验证会带响应头 cf-mitigated: challenge，状态码 403 / 503；
     * 其它防护通常只能靠「错误状态码 + 验证页标题」判断。
     */
    fun isChallenge(status: Int, cfMitigated: String?, server: String?, title: String?): Boolean {
        if (cfMitigated.equals("challenge", ignoreCase = true)) return true
        if (!isChallengeTitle(title)) return false
        return status >= 400 || server.orEmpty().contains("cloudflare", ignoreCase = true) ||
            server.orEmpty().contains("ddos-guard", ignoreCase = true)
    }

    /** 把用户输入的「a.com, b.com 换行 c.com」拆成规范化的域名列表 */
    fun parseHosts(text: String): List<String> =
        text.split(Regex("[,，;；\\s]+"))
            .map { it.trim().lowercase().removePrefix("*.").removePrefix(".").removeSuffix("/") }
            .map { it.substringAfter("://").substringBefore('/').substringBefore(':') } // 白名单只比较域名，不含端口
            .filter { it.isNotEmpty() }
            .distinct()
}
