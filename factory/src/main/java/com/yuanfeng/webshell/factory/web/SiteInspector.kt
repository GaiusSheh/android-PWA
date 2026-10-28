package com.yuanfeng.webshell.factory.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.yuanfeng.webshell.factory.core.SiteRules
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * 打开网页，读取适合做 App 的名称、图标和主题色，用于自动预填。
 *
 * 信息来源按可靠程度排序：
 * - 名称：Web App Manifest 的 short_name / name → <meta name="application-name"> →
 *         apple-mobile-web-app-title → og:site_name → <title>（取第一个分隔符前的部分）
 * - 图标：manifest icons → apple-touch-icon → <link rel="icon"> → /apple-touch-icon.png → /favicon.ico
 * - 颜色：<meta name="theme-color">（跳过只用于深色模式的那条）→ manifest theme_color
 *
 * 全部是阻塞的网络操作，必须在后台线程调用。
 *
 * 有人机验证（如 Cloudflare 的「Just a moment...」）的网站，直接请求只能拿到验证页：
 * [inspect] 识别出来后返回 [Outcome.Challenge]，由调用方用真正的 WebView（[BrowserFetcher]）通过验证，
 * 再把渲染好的 HTML 交给 [inspectHtml] 解析。
 *
 * @param cookies 按网址取 Cookie。传入 WebView 的 CookieManager 后，下载 manifest / 图标时会带上
 *                通过验证后拿到的 Cookie（Cloudflare 的 cf_clearance 与 User-Agent 绑定，所以 UA 也必须一致）
 */
class SiteInspector(
    private val userAgent: String,
    private val cookies: (String) -> String? = { null },
) {

    class IconCandidate(val bitmap: Bitmap, val source: String)

    class Result(
        val finalUrl: String,
        val name: String?,
        val colors: List<Int>,
        val icons: List<IconCandidate>,
    )

    sealed interface Outcome {
        class Ok(val result: Result) : Outcome
        /** 拿到的是人机验证页；url 是重定向后的最终地址 */
        class Challenge(val url: String) : Outcome
    }

    private class IconRef(val url: String, val priority: Int, val declaredSize: Int, val source: String)

    /** 直接请求网页并解析；遇到人机验证页时不解析，返回 [Outcome.Challenge] */
    fun inspect(url: String): Outcome {
        val response = connect(url)
            .timeout(15_000)
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .maxBodySize(5 * 1024 * 1024)
            .execute()
        val doc = response.parse()
        val finalUrl = response.url().toString()
        if (SiteRules.isChallenge(response.statusCode(), response.header("cf-mitigated"), response.header("server"), doc.title())) {
            return Outcome.Challenge(finalUrl)
        }
        return Outcome.Ok(extract(doc, finalUrl))
    }

    /** 解析 WebView 渲染出的 HTML（已通过人机验证） */
    fun inspectHtml(html: String, pageUrl: String): Result = extract(Jsoup.parse(html, pageUrl), pageUrl)

    private fun connect(url: String) = Jsoup.connect(url).userAgent(userAgent).also { c ->
        cookies(url)?.takeIf { it.isNotBlank() }?.let { c.header("Cookie", it) }
    }

    private fun extract(doc: Document, finalUrl: String): Result {
        val manifest = loadManifest(doc)

        val name = listOfNotNull(
            manifest?.optString("short_name"),
            manifest?.optString("name"),
            doc.selectFirst("meta[name=application-name]")?.attr("content"),
            doc.selectFirst("meta[name=apple-mobile-web-app-title]")?.attr("content"),
            doc.selectFirst("meta[property=og:site_name]")?.attr("content"),
            cleanTitle(doc.title()).takeUnless { SiteRules.isChallengeTitle(it) },
        ).map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(40)

        val colors = buildList {
            doc.select("meta[name=theme-color]")
                .filterNot { it.attr("media").contains("dark", ignoreCase = true) }
                .forEach { SiteRules.parseCssColor(it.attr("content"))?.let(::add) }
            SiteRules.parseCssColor(manifest?.optString("theme_color"))?.let(::add)
        }.distinct()

        val icons = collectIconRefs(doc, manifest, finalUrl)
            .distinctBy { it.url }
            .take(MAX_ICON_DOWNLOADS)
            .mapNotNull { ref -> download(ref)?.let { ref to it } }
            .sortedWith(
                compareBy<Pair<IconRef, Bitmap>> { if (minOf(it.second.width, it.second.height) >= 96) 0 else 1 }
                    .thenBy { it.first.priority }
                    .thenByDescending { it.second.width }
            )
            .map { IconCandidate(it.second, it.first.source) }

        return Result(finalUrl, name, colors, icons)
    }

    private fun loadManifest(doc: Document): JSONObject? {
        val href = doc.selectFirst("link[rel=manifest]")?.absUrl("href")?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val body = connect(href).ignoreContentType(true).timeout(10_000).execute().body()
            JSONObject(body)
        }.getOrNull()
    }

    private fun collectIconRefs(doc: Document, manifest: JSONObject?, pageUrl: String): List<IconRef> {
        val refs = mutableListOf<IconRef>()
        manifest?.optJSONArray("icons")?.let { arr ->
            val base = doc.selectFirst("link[rel=manifest]")?.absUrl("href") ?: pageUrl
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val src = o.optString("src").takeIf { it.isNotEmpty() } ?: continue
                if (o.optString("type").contains("svg") || src.endsWith(".svg")) continue // 不渲染 SVG
                val abs = runCatching { URI(base).resolve(src).toString() }.getOrNull() ?: continue
                val maskable = o.optString("purpose").contains("maskable")
                refs += IconRef(abs, if (maskable) 2 else 0, maxSize(o.optString("sizes")), "manifest")
            }
        }
        for (link in doc.select("link[rel~=(?i)apple-touch-icon]")) {
            refs += IconRef(link.absUrl("href"), 1, maxSize(link.attr("sizes")).takeIf { it > 0 } ?: 180, "apple-touch-icon")
        }
        for (link in doc.select("link[rel~=(?i)(^|\\s)icon(\\s|$)]")) {
            val href = link.absUrl("href")
            if (link.attr("type").contains("svg") || href.endsWith(".svg")) continue
            refs += IconRef(href, 3, maxSize(link.attr("sizes")), "icon")
        }
        val origin = runCatching { URI(pageUrl).let { "${it.scheme}://${it.authority}" } }.getOrNull()
        if (origin != null) {
            refs += IconRef("$origin/apple-touch-icon.png", 4, 180, "apple-touch-icon")
            refs += IconRef("$origin/favicon.ico", 5, 32, "favicon")
        }
        // 声明尺寸大的优先下载（下载数有上限）
        return refs.filter { it.url.startsWith("http") }.sortedWith(compareBy<IconRef> { it.priority }.thenByDescending { it.declaredSize })
    }

    private fun download(ref: IconRef): Bitmap? = runCatching {
        val bytes = connect(ref.url).ignoreContentType(true)
            .timeout(10_000).maxBodySize(3 * 1024 * 1024).execute().bodyAsBytes()
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()?.takeIf { minOf(it.width, it.height) >= 16 }

    /** "192x192 512x512" → 512；"any" → 1024 */
    private fun maxSize(sizes: String): Int = sizes.split(' ')
        .mapNotNull { s -> if (s.equals("any", true)) 1024 else s.lowercase().substringBefore('x').toIntOrNull() }
        .maxOrNull() ?: 0

    /** "Hacker News | 首页" → "Hacker News" */
    private fun cleanTitle(title: String): String =
        title.split(Regex("""\s+[-|–—·:：]\s+|\s*[|｜]\s*""")).firstOrNull()?.trim().orEmpty()

    private companion object {
        const val MAX_ICON_DOWNLOADS = 8
    }
}
