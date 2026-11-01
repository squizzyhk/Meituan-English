package com.mtenglish.app.ui

import com.mtenglish.app.ui.MeituanLinks.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MeituanLinksTest {

    private fun ok(input: String) = (MeituanLinks.parse(input) as Result.Ok).url

    @Test fun plainLinks() {
        assertEquals("https://i.meituan.com/", ok("https://i.meituan.com/"))
        assertEquals("https://m.dianping.com/shop/12345", ok("  https://m.dianping.com/shop/12345  "))
        assertEquals("https://p0.meituan.net/a.jpg", ok("https://p0.meituan.net/a.jpg"))
    }

    @Test fun httpIsUpgraded() {
        assertEquals("https://m.dianping.com/shop/1?x=2", ok("http://m.dianping.com/shop/1?x=2"))
        assertEquals("https://meituan.com/", ok("HTTP://MEITUAN.COM"))
    }

    @Test fun shareTextWithChinese() {
        assertEquals("https://dpurl.cn/aBc123", ok("【大众点评】这家店超好吃！https://dpurl.cn/aBc123 复制链接打开"))
        assertEquals("https://dpurl.cn/aBc123", ok("快来看看https://dpurl.cn/aBc123，打开大众点评"))
        assertEquals("https://h5.waimai.meituan.com/waimai/mindex/menu?poi=1", ok("推荐给你 (https://h5.waimai.meituan.com/waimai/mindex/menu?poi=1)."))
    }

    @Test fun bareDomain() {
        assertEquals("https://m.dianping.com/shop/9", ok("看看 m.dianping.com/shop/9 吧"))
        assertEquals("https://i.meituan.com/", ok("i.meituan.com"))
    }

    @Test fun prefersMeituanLinkAmongSeveral() {
        assertEquals("https://dpurl.cn/x", ok("https://example.com/a and https://dpurl.cn/x"))
    }

    @Test fun rejectsOtherSites() {
        assertTrue(MeituanLinks.parse("https://example.com/") is Result.NotMeituan)
        assertTrue(MeituanLinks.parse("https://evilmeituan.com/") is Result.NotMeituan)
        assertTrue(MeituanLinks.parse("https://meituan.com.evil.org/") is Result.NotMeituan)
        assertTrue(MeituanLinks.parse("https://meituan.com@evil.org/") is Result.NotMeituan)
    }

    @Test fun rejectsNonWebSchemes() {
        assertTrue(MeituanLinks.parse("imeituan://www.meituan.com/home") is Result.NotWeb)
        assertTrue(MeituanLinks.parse("ftp://meituan.com/file") is Result.NotWeb)
    }

    @Test fun noLink() {
        assertTrue(MeituanLinks.parse("") is Result.NoLink)
        assertTrue(MeituanLinks.parse(null) is Result.NoLink)
        assertTrue(MeituanLinks.parse("宫保鸡丁 好吃") is Result.NoLink)
        assertTrue(MeituanLinks.parse("javascript:alert(1)") is Result.NoLink)
    }

    @Test fun keepsPort() {
        assertEquals("https://i.meituan.com:8443/x", ok("https://i.meituan.com:8443/x"))
    }
}
