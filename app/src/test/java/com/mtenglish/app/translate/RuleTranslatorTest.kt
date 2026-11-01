package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleTranslatorTest {
    private val rules = RuleTranslator(
        mapOf(
            "全部" to "All",
            "宫保鸡丁" to "Kung Pao Chicken",
            "微辣" to "Mild spicy",
            "去结算" to "Checkout",
            "收货地址" to "Delivery address",
            "招牌" to "Signature",
            "美团专送" to "Meituan Delivery",
            "套餐" to "Set Meal",
            "姓名" to "Name",
            "查看全部" to "View all",
            "饮品" to "Drinks",
        ),
    )

    private fun t(s: String) = rules.translate(s)

    @Test fun glossaryExact() {
        assertEquals("Kung Pao Chicken", t("宫保鸡丁"))
        assertEquals("Kung Pao Chicken", t("  宫保鸡丁　"))
        assertEquals("Checkout", t("去结算"))
    }

    @Test fun affixes() {
        assertEquals("All (23)", t("全部(23)"))
        assertEquals("All (1,200+)", t("全部（1200+）"))
        assertEquals("Delivery address:", t("收货地址："))
        assertEquals("*Name", t("*姓名"))
        assertEquals("[Signature]", t("【招牌】"))
        assertEquals("Set Meal 2", t("套餐2"))
        assertEquals("View all >", t("查看全部 >"))
        assertEquals("View all>", t("查看全部>"))
    }

    @Test fun composites() {
        assertEquals("★ 4.8 · 1,000+ sold/month · ¥35 per person", t("4.8分 月售1000+ 人均¥35"))
        assertEquals("¥20 min. order | ¥3 delivery", t("起送¥20 | 配送¥3"))
        assertEquals("¥20 min. order · ¥3 delivery", t("起送¥20·配送¥3"))
        assertEquals("¥5 off orders over ¥30, ¥10 off orders over ¥50", t("满30减5，满50减10"))
        assertEquals("1,000+ sold/month", t("月售 1,000+"))
        assertEquals("[Signature] Kung Pao Chicken", t("【招牌】宫保鸡丁"))
        assertEquals("Kung Pao Chicken (Mild spicy)", t("宫保鸡丁（微辣）"))
        assertEquals("Meituan Delivery · ~30 min · 1.2 km", t("美团专送 约30分钟 1.2km"))
        assertEquals("#1 best seller in Drinks", t("饮品热销第1名"))
        assertEquals("Kung Pao Chicken x2", t("宫保鸡丁 x2"))
        assertEquals("Kung Pao Chicken · Mild spicy", t("宫保鸡丁 微辣"))
        assertEquals("¥15 min. order · ¥2.5 delivery", t("起送 ¥15 配送 ¥2.5"))
    }

    @Test fun latinDistancesAndSeparators() {
        assertEquals("~30 min | 3.2 km", t("约30分钟 | 3.2km"))
        assertEquals("1.2 km · ~30 min", t("1.2km 约30分钟"))
        assertEquals("~30 min · 1.2 km", t("约30分钟 1.2km"))
    }

    @Test fun refusesPartial() {
        assertNull(t("宫保鸡丁 鱼香肉丝"))
        assertNull(t("老王家牛肉面"))
        assertNull(t("hello"))
        assertNull(t(""))
    }
}
