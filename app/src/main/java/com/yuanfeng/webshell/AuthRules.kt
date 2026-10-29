package com.yuanfeng.webshell

/**
 * 第三方登录（OAuth / SSO）的「入口」规则：主框架跳到这些地址，就认为用户开始了一次登录。
 *
 * 为什么需要：壳默认把 allowedHosts 以外的链接交给系统浏览器。可登录必须整个过程都在同一个
 * WebView 里完成，否则登录写下的 Cookie 在浏览器里，App 拿不到，回调时还会因为会话对不上而失败。
 * 但也不能简单把 google.com、github.com 加进白名单，那样网站里普通的 YouTube 视频、GitHub 仓库
 * 链接也会在 App 内打开。所以只匹配「登录专用」的域名和路径；进入登录后的中间页（地区域名、
 * 企业 SSO 等）由 ShellWebViewClient 的登录流程状态统一放行。
 *
 * 规则写死在代码里、所有 App 相同，不放进 config.json（工厂只能替换文件，见 ShellConfig 的说明）。
 * 只用字符串处理、不依赖 android.net.Uri，方便在 JVM 上单元测试。
 */
object AuthRules {

    /** 整个域名都只用于登录 */
    private val WHOLE_HOSTS = setOf(
        "accounts.google.com",
        // Google 登录成功后会跳到这里，在 YouTube 域名下也写入登录 Cookie
        "accounts.youtube.com",
        "login.microsoftonline.com",
        "login.live.com",
        "login.microsoft.com",
        "appleid.apple.com",
    )

    /**
     * Google 的国家 / 地区域名后缀（不含开头的 "google."），来自 https://www.google.com/supported_domains 。
     * 登录时 Google 会跳到用户所在地区的 accounts.google.<后缀>/accounts/SetSID 写 Cookie，
     * 例如英国是 accounts.google.co.uk。用官方列表而不是正则，避免误放行不属于 Google 的域名。
     */
    private val GOOGLE_SUFFIXES = setOf(
        "com", "ad", "ae", "com.af", "com.ag", "al", "am", "co.ao", "com.ar", "as", "at", "com.au", "az", "ba",
        "com.bd", "be", "bf", "bg", "com.bh", "bi", "bj", "com.bn", "com.bo", "com.br", "bs", "bt", "co.bw",
        "by", "com.bz", "ca", "cd", "cf", "cg", "ch", "ci", "co.ck", "cl", "cm", "cn", "com.co", "co.cr",
        "com.cu", "cv", "com.cy", "cz", "de", "dj", "dk", "dm", "com.do", "dz", "com.ec", "ee", "com.eg", "es",
        "com.et", "fi", "com.fj", "fm", "fr", "ga", "ge", "gg", "com.gh", "com.gi", "gl", "gm", "gr", "com.gt",
        "gy", "com.hk", "hn", "hr", "ht", "hu", "co.id", "ie", "co.il", "im", "co.in", "iq", "is", "it", "je",
        "com.jm", "jo", "co.jp", "co.ke", "com.kh", "ki", "kg", "co.kr", "com.kw", "kz", "la", "com.lb", "li",
        "lk", "co.ls", "lt", "lu", "lv", "com.ly", "co.ma", "md", "me", "mg", "mk", "ml", "com.mm", "mn",
        "com.mt", "mu", "mv", "mw", "com.mx", "com.my", "co.mz", "com.na", "com.ng", "com.ni", "ne", "nl",
        "no", "com.np", "nr", "nu", "co.nz", "com.om", "com.pa", "com.pe", "com.pg", "com.ph", "com.pk", "pl",
        "pn", "com.pr", "ps", "pt", "com.py", "com.qa", "ro", "ru", "rw", "com.sa", "com.sb", "sc", "se",
        "com.sg", "sh", "si", "sk", "com.sl", "sn", "so", "sm", "sr", "st", "com.sv", "td", "tg", "co.th",
        "com.tj", "tl", "tm", "tn", "to", "com.tr", "tt", "com.tw", "co.tz", "com.ua", "co.ug", "co.uk",
        "com.uy", "co.uz", "com.vc", "co.ve", "co.vi", "com.vn", "vu", "ws", "rs", "co.za", "co.zm", "co.zw",
        "cat",
    )

    /** 和普通内容共用的域名：只有这些路径算登录入口 */
    private class PathRule(val hosts: Set<String>, val paths: List<Regex>)

    private val PATH_RULES = listOf(
        // GitHub：/login、/login/oauth/authorize、/sessions/two-factor 等
        PathRule(setOf("github.com"), listOf(Regex("^/login(/.*)?$"), Regex("^/sessions(/.*)?$"))),
        PathRule(
            setOf("facebook.com", "www.facebook.com", "m.facebook.com"),
            listOf(Regex("^(/v[0-9.]+)?/dialog/oauth(/.*)?$"), Regex("^/login(\\.php)?(/.*)?$")),
        ),
        PathRule(setOf("x.com", "twitter.com"), listOf(Regex("^/i/oauth2/authorize$"))),
        // OAuth 1.0a：authorize 是授权，authenticate 是「用 X 登录」
        PathRule(setOf("api.x.com", "api.twitter.com"), listOf(Regex("^/oauth/(authorize|authenticate)$"))),
        PathRule(setOf("discord.com"), listOf(Regex("^(/api)?/oauth2/authorize$"), Regex("^/login$"))),
        PathRule(
            setOf("reddit.com", "www.reddit.com"),
            listOf(Regex("^/api/v1/authorize(\\.compact)?$"), Regex("^/login(/.*)?$")),
        ),
        PathRule(setOf("linkedin.com", "www.linkedin.com"), listOf(Regex("^/oauth/v2/authorization$"))),
        PathRule(setOf("open.weixin.qq.com"), listOf(Regex("^/connect/.*"))),
        PathRule(setOf("graph.qq.com"), listOf(Regex("^/oauth2\\.0/.*"))),
        PathRule(setOf("api.weibo.com"), listOf(Regex("^/oauth2/.*"))),
    )

    /**
     * @param host 目标地址的域名（大小写不限）
     * @param path 目标地址的路径，不含 ? 之后的查询参数；空或 null 视为 "/"
     */
    fun isAuthEntry(host: String?, path: String?): Boolean {
        val h = host?.lowercase() ?: return false
        if (h in WHOLE_HOSTS) return true
        if (h.startsWith(GOOGLE_ACCOUNTS_PREFIX) && h.removePrefix(GOOGLE_ACCOUNTS_PREFIX) in GOOGLE_SUFFIXES) return true
        val p = path?.ifEmpty { null } ?: "/"
        return PATH_RULES.any { rule -> h in rule.hosts && rule.paths.any { it.matches(p) } }
    }

    private const val GOOGLE_ACCOUNTS_PREFIX = "accounts.google."
}
