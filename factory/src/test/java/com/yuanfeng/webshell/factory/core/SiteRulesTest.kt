package com.yuanfeng.webshell.factory.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteRulesTest {

    @Test
    fun baseDomain() {
        assertEquals("ycombinator.com", SiteRules.baseDomain("news.ycombinator.com"))
        assertEquals("bbc.co.uk", SiteRules.baseDomain("www.bbc.co.uk"))
        assertEquals("example.com.cn", SiteRules.baseDomain("m.example.com.cn"))
        assertEquals("wikipedia.org", SiteRules.baseDomain("en.m.wikipedia.org"))
        assertEquals("192.168.1.2", SiteRules.baseDomain("192.168.1.2"))
        assertEquals("localhost", SiteRules.baseDomain("localhost"))
    }

    @Test
    fun suggestId() {
        assertEquals("news_ycombinator", SiteRules.suggestId("news.ycombinator.com", emptySet()))
        assertEquals("bilibili", SiteRules.suggestId("www.bilibili.com", emptySet()))
        assertEquals("bilibili_2", SiteRules.suggestId("m.bilibili.com", setOf("bilibili")))
        assertEquals("example", SiteRules.suggestId("example.com.cn", emptySet()))
        assertEquals("s192_168_1", SiteRules.suggestId("192.168.1.2", emptySet()))
        assertEquals("my_site", SiteRules.suggestId("my-site.io", emptySet()))
        // 保留字（Java 关键字）自动加后缀
        assertEquals("new_app", SiteRules.suggestId("new.org", emptySet()))
        assertTrue(SiteRules.isValidId(SiteRules.suggestId("中文.com", emptySet())))
    }

    @Test
    fun parseCssColor() {
        assertEquals(0xFFFF6600.toInt(), SiteRules.parseCssColor("#ff6600"))
        assertEquals(0xFFFFFFFF.toInt(), SiteRules.parseCssColor("#FFF"))
        assertEquals(0x80112233.toInt(), SiteRules.parseCssColor("#11223380"))
        assertEquals(0xFF0A141E.toInt(), SiteRules.parseCssColor("rgb(10, 20, 30)"))
        assertEquals(0x7F0A141E, SiteRules.parseCssColor("rgba(10,20,30,0.5)"))
        assertEquals(0xFFFFFFFF.toInt(), SiteRules.parseCssColor("white"))
        assertNull(SiteRules.parseCssColor("var(--x)"))
        assertNull(SiteRules.parseCssColor("#12"))
    }

    @Test
    fun normalizeUrl() {
        assertEquals("https://example.com", SiteRules.normalizeUrl(" example.com "))
        assertEquals("http://10.0.0.1:8123/", SiteRules.normalizeUrl("http://10.0.0.1:8123/"))
        assertNull(SiteRules.normalizeUrl("ftp://x.com"))
        assertNull(SiteRules.normalizeUrl(""))
    }

    @Test
    fun parseHosts() {
        assertEquals(listOf("a.com", "b.com", "c.com"), SiteRules.parseHosts("a.com, *.b.com\nhttps://c.com/x，a.com"))
        assertEquals(listOf("10.0.0.1"), SiteRules.parseHosts("10.0.0.1:8123"))
    }

    @Test
    fun ids() {
        assertTrue(SiteRules.isValidId("hackernews"))
        assertFalse(SiteRules.isValidId("class"))
        assertFalse(SiteRules.isValidId("factory"))
        assertFalse(SiteRules.isValidId("1abc"))
        assertFalse(SiteRules.isValidId("testing"))
    }

    @Test
    fun challengeDetection() {
        // Cloudflare JS 验证：有专门的响应头
        assertTrue(SiteRules.isChallenge(403, "challenge", "cloudflare", "Just a moment..."))
        assertTrue(SiteRules.isChallenge(200, "challenge", null, "anything"))
        // 没有响应头时，靠错误状态码或服务器标识 + 标题
        assertTrue(SiteRules.isChallenge(503, null, null, "Just a moment..."))
        assertTrue(SiteRules.isChallenge(200, null, "cloudflare", "Attention Required! | Cloudflare"))
        assertTrue(SiteRules.isChallenge(403, null, null, "请稍候…"))
        // 普通网站，或者标题碰巧相似但状态正常、不是防护服务器
        assertFalse(SiteRules.isChallenge(200, null, "cloudflare", "Hacker News"))
        assertFalse(SiteRules.isChallenge(200, null, "nginx", "Please wait for our launch"))
        assertFalse(SiteRules.isChallenge(404, null, null, "Not Found"))
        assertTrue(SiteRules.isChallengeTitle("Just a moment..."))
        assertFalse(SiteRules.isChallengeTitle("npm | Home"))
    }

    @Test
    fun configJsonEscapes() {
        val json = SiteRules.configJson("https://a.com/?q=\"x\"", listOf("a.com"), 0xFF000000.toInt(), "UA\\1", false, true)
        assertTrue(json, json.contains("\"url\": \"https://a.com/?q=\\\"x\\\"\""))
        assertTrue(json, json.contains("\"userAgent\": \"UA\\\\1\""))
        assertTrue(json, json.contains("\"themeColor\": \"#FF000000\""))
    }
}
