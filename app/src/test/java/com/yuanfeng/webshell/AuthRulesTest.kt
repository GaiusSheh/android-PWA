package com.yuanfeng.webshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRulesTest {

    private fun entry(host: String, path: String = "/") = AuthRules.isAuthEntry(host, path)

    @Test
    fun googleLoginChain() {
        // 2026-10-29 真机实测的完整跳转链（见 .dev/tests/google-login/findings.md）
        assertTrue(entry("accounts.google.com", "/o/oauth2/auth"))
        assertTrue(entry("accounts.google.com", "/CheckCookie"))
        assertTrue(entry("accounts.youtube.com", "/accounts/SetSID"))
        assertTrue(entry("accounts.google.co.uk", "/accounts/SetSID"))
        assertTrue(entry("accounts.google.com.hk", "/accounts/SetSID"))
        assertTrue(entry("accounts.google.de", "/accounts/SetSID"))
        assertTrue(entry("ACCOUNTS.GOOGLE.COM", "/"))
    }

    @Test
    fun googleNonLoginPagesAreNotEntries() {
        assertFalse(entry("www.google.com", "/search"))
        assertFalse(entry("www.youtube.com", "/watch"))
        assertFalse(entry("youtube.com", "/accounts/SetSID"))
        assertFalse(entry("docs.google.com", "/document/d/x"))
        // 不在官方列表里的后缀不放行
        assertFalse(entry("accounts.google.example", "/"))
        assertFalse(entry("accounts.google.co.uk.evil.com", "/"))
        assertFalse(entry("evilaccounts.google.com", "/"))
    }

    @Test
    fun wholeHostProviders() {
        assertTrue(entry("login.microsoftonline.com", "/common/oauth2/v2.0/authorize"))
        assertTrue(entry("login.live.com", "/oauth20_authorize.srf"))
        assertTrue(entry("appleid.apple.com", "/auth/authorize"))
    }

    @Test
    fun github() {
        assertTrue(entry("github.com", "/login"))
        assertTrue(entry("github.com", "/login/oauth/authorize"))
        assertTrue(entry("github.com", "/sessions/two-factor/app"))
        assertFalse(entry("github.com", "/"))
        assertFalse(entry("github.com", "/torvalds/linux"))
        assertFalse(entry("github.com", "/loginx"))
        assertFalse(entry("github.com", "/someone/login"))
    }

    @Test
    fun otherPathProviders() {
        assertTrue(entry("www.facebook.com", "/v19.0/dialog/oauth"))
        assertTrue(entry("m.facebook.com", "/login.php"))
        assertFalse(entry("www.facebook.com", "/somepage"))

        assertTrue(entry("x.com", "/i/oauth2/authorize"))
        assertTrue(entry("api.x.com", "/oauth/authenticate"))
        assertFalse(entry("x.com", "/someone/status/1"))

        assertTrue(entry("discord.com", "/oauth2/authorize"))
        assertTrue(entry("discord.com", "/api/oauth2/authorize"))
        assertFalse(entry("discord.com", "/channels/1/2"))

        assertTrue(entry("www.reddit.com", "/api/v1/authorize.compact"))
        assertFalse(entry("www.reddit.com", "/r/Physics"))

        assertTrue(entry("www.linkedin.com", "/oauth/v2/authorization"))
        assertFalse(entry("www.linkedin.com", "/in/someone"))

        assertTrue(entry("open.weixin.qq.com", "/connect/qrconnect"))
        assertTrue(entry("graph.qq.com", "/oauth2.0/authorize"))
        assertTrue(entry("api.weibo.com", "/oauth2/authorize"))
        assertFalse(entry("weibo.com", "/oauth2/authorize"))
    }

    @Test
    fun missingParts() {
        assertFalse(AuthRules.isAuthEntry(null, "/login"))
        assertFalse(AuthRules.isAuthEntry("github.com", null))
        assertTrue(AuthRules.isAuthEntry("accounts.google.com", null))
        assertTrue(AuthRules.isAuthEntry("accounts.google.com", ""))
    }
}
