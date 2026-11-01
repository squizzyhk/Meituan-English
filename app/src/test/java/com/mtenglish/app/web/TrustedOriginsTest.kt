package com.mtenglish.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedOriginsTest {

    @Test fun trustedUrls() {
        assertTrue(TrustedOrigins.isTrustedUrl("https://h5.waimai.meituan.com/waimai/mindex/home"))
        assertTrue(TrustedOrigins.isTrustedUrl("https://i.meituan.com"))
        assertTrue(TrustedOrigins.isTrustedUrl("https://m.dianping.com/"))
        assertTrue(TrustedOrigins.isTrustedUrl("https://meituan.com"))
        assertFalse(TrustedOrigins.isTrustedUrl("http://i.meituan.com/"))
        assertFalse(TrustedOrigins.isTrustedUrl("https://evilmeituan.com/"))
        assertFalse(TrustedOrigins.isTrustedUrl("https://meituan.com.evil.example/"))
        assertFalse(TrustedOrigins.isTrustedUrl("https://ads.example.com/frame"))
        assertFalse(TrustedOrigins.isTrustedUrl("null"))
        assertFalse(TrustedOrigins.isTrustedUrl(null))
    }

    @Test fun originRulesCoverApexAndSubdomains() {
        assertTrue("https://meituan.com" in TrustedOrigins.ORIGIN_RULES)
        assertTrue("https://*.meituan.com" in TrustedOrigins.ORIGIN_RULES)
        assertTrue("https://*.dianping.com" in TrustedOrigins.ORIGIN_RULES)
        assertFalse(TrustedOrigins.ORIGIN_RULES.contains("*"))
    }

    @Test fun siteOf() {
        assertEquals("meituan.com", TrustedOrigins.siteOf("h5.waimai.meituan.com"))
        assertEquals("meituan.net", TrustedOrigins.siteOf("p0.meituan.net"))
        assertEquals("example.com.cn", TrustedOrigins.siteOf("a.b.example.com.cn"))
        assertNull(TrustedOrigins.siteOf(null))
    }

    @Test fun cookiesOnlyToTheSameTrustedSite() {
        assertTrue(WebImageServer.shouldSendCookies("https://img.meituan.com/a.png", "https://i.meituan.com/x"))
        assertFalse(WebImageServer.shouldSendCookies("https://p0.meituan.net/a.png", "https://i.meituan.com/x"))
        assertFalse(WebImageServer.shouldSendCookies("https://img.example.com/a.png", "https://i.meituan.com/x"))
        assertFalse(WebImageServer.shouldSendCookies("https://img.meituan.com/a.png", null))
    }

    @Test fun corsOnlyForTrustedOrigins() {
        assertEquals("https://i.meituan.com", WebImageServer.corsHeaders(mapOf("Origin" to "https://i.meituan.com"))["Access-Control-Allow-Origin"])
        assertNull(WebImageServer.corsHeaders(mapOf("Origin" to "https://ads.example.com"))["Access-Control-Allow-Origin"])
        assertNull(WebImageServer.corsHeaders(emptyMap())["Access-Control-Allow-Origin"])
        assertNull(WebImageServer.corsHeaders(null)["Access-Control-Allow-Origin"])
    }
}
