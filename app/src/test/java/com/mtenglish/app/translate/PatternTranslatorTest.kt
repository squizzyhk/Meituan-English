package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PatternTranslatorTest {

    private fun t(input: String) = PatternTranslator.translate(input)

    private fun check(vararg cases: Pair<String, String>) {
        val failures = cases.mapNotNull { (zh, en) ->
            val got = t(zh)
            if (got == en) null else "\"$zh\": expected \"$en\" but got \"$got\""
        }
        assertEquals(failures.joinToString("\n"), 0, failures.size)
    }

    @Test fun salesVolume() = check(
        "月售1000+" to "1,000+ sold/month",
        "月售 230" to "230 sold/month",
        "月售1.2万+" to "12,000+ sold/month",
        "月销量999+" to "999+ sold/month",
        "已售1万+" to "10,000+ sold",
        "已售 52 份" to "52 sold",
        "销量 3456" to "3,456 sold",
        "年售10万+" to "100,000+ sold/year",
    )

    @Test fun minOrderAndDelivery() = check(
        "起送¥20" to "¥20 min. order",
        "起送 ￥15" to "¥15 min. order",
        "¥20起送" to "¥20 min. order",
        "20元起送" to "¥20 min. order",
        "配送¥3" to "¥3 delivery",
        "配送费¥3.5" to "¥3.5 delivery",
        "配送费：¥2" to "¥2 delivery",
        "配送约¥4" to "~¥4 delivery",
        "免配送费" to "Free delivery",
        "配送费¥0" to "Free delivery",
        "减配送费¥2" to "¥2 off delivery",
        "另需配送费¥5" to "+¥5 delivery",
        "满39免配送费" to "Free delivery over ¥39",
        "人均¥35" to "¥35 per person",
        "人均 ￥128" to "¥128 per person",
        "还差¥8起送" to "Add ¥8 more to order",
        "再买¥10可减5元" to "Add ¥10 more to save ¥5",
    )

    @Test fun promotions() = check(
        "满30减5" to "¥5 off orders over ¥30",
        "满100元减20元" to "¥20 off orders over ¥100",
        "满30减5满50减10" to "¥5 off orders over ¥30, ¥10 off orders over ¥50",
        "每满50减10" to "¥10 off every ¥50",
        "8.5折" to "15% off",
        "8折" to "20% off",
        "5折起" to "Up to 50% off",
        "低至3.8折" to "Up to 62% off",
        "全场9折" to "10% off storewide",
        "新客立减¥8" to "New customers: ¥8 off",
        "首单立减10元" to "¥10 off first order",
        "立减5元" to "¥5 off",
        "已优惠¥12.5" to "¥12.5 saved",
        "5元红包" to "¥5 voucher",
        "¥10无门槛" to "¥10 off, no minimum",
        "第二份半价" to "2nd item half price",
        "满50可用" to "Min. spend ¥50",
        "券后¥19.9" to "¥19.9 after coupon",
    )

    @Test fun times() = check(
        "约30分钟" to "~30 min",
        "30分钟" to "30 min",
        "30-40分钟" to "30–40 min",
        "配送约30分钟" to "~30 min delivery",
        "约35分钟送达" to "Arrives in ~35 min",
        "1小时" to "1 hr",
        "2小时" to "2 hrs",
        "约1小时20分钟" to "~1 hr 20 min",
        "步行5分钟" to "5 min walk",
        "今天 12:30送达" to "Arrives today 12:30",
        "明天11:30送达" to "Arrives tomorrow 11:30",
        "预计12:45送达" to "Arrives by 12:45",
        "预计送达时间 18:20" to "Est. arrival 18:20",
        "营业时间 10:00-22:00" to "Open 10:00–22:00",
        "营业时间：10:00-14:00，17:00-次日02:00" to "Open 10:00–14:00, 17:00–02:00 (next day)",
        "24小时营业" to "Open 24 hours",
        "明天10:00开始营业" to "Opens tomorrow at 10:00",
        "距结束 02:13:45" to "Ends in 02:13:45",
    )

    @Test fun relativeTimeAndDates() = check(
        "3天前" to "3 days ago",
        "1天前" to "1 day ago",
        "5小时前" to "5 hours ago",
        "12分钟前" to "12 minutes ago",
        "刚刚" to "Just now",
        "2个月前" to "2 months ago",
        "昨天" to "Yesterday",
        "昨天 18:20" to "Yesterday 18:20",
        "3月5日" to "Mar 5",
        "2024年12月25日" to "Dec 25, 2024",
        "6月18日 周二" to "Tue, Jun 18",
        "周一" to "Monday",
        "星期日" to "Sunday",
        "周一至周五" to "Mon–Fri",
        "每周三" to "Every Wednesday",
    )

    @Test fun distanceAndRatings() = check(
        "距离1.2km" to "1.2 km away",
        "2.5公里" to "2.5 km",
        "距您800米" to "800 m away",
        "<500米" to "<500 m",
        "4.8分" to "★ 4.8",
        "5分" to "★ 5",
        "评分4.6" to "Rated 4.6",
        "口味4.9" to "Taste 4.9",
        "4.5星" to "4.5 stars",
        "五星级酒店" to "5-star hotel",
        "1234条评价" to "1,234 reviews",
        "1条评论" to "1 comment",
        "999+条评价" to "999+ reviews",
        "好评率98%" to "98% positive",
        "好评(120)" to "Positive (120)",
        "有图 56" to "With Photos (56)",
    )

    @Test fun stockQuantitiesRanks() = check(
        "剩余3份" to "3 left",
        "仅剩2件" to "Only 2 left",
        "限购2份" to "Limit 2",
        "每人限购1份" to "Limit 1 per person",
        "¥28起" to "From ¥28",
        "¥12/份" to "¥12/serving",
        "¥38/斤" to "¥38/500g",
        "15元" to "¥15",
        "1288元" to "¥1,288",
        "x2份" to "×2",
        "2份" to "2 servings",
        "1份" to "1 serving",
        "3个" to "3 pcs",
    )

    @Test fun moreStockQuantitiesRanks() = check(
        "1斤" to "500g",
        "500克" to "500g",
        "330毫升" to "330ml",
        "共3件商品" to "3 items",
        "共1件" to "1 item",
        "合计¥56.5" to "Total ¥56.5",
        "实付¥48" to "Total ¥48",
        "包装费¥2" to "Packaging fee ¥2",
        "原价¥30" to "Was ¥30",
        "1000+人付款" to "1,000+ bought",
        "2.3万人想去" to "23,000 want to go",
        "销量第1名" to "#1 best seller",
        "热销第3名" to "#3 best seller",
        "第2名" to "#2",
        "双人套餐" to "Meal for 2",
        "2-3人餐" to "Meal for 2–3",
        "3人份" to "Serves 3",
        "7天内有效" to "Valid for 7 days",
        "已选2件" to "2 selected",
    )

    @Test fun reviewFixesDeliveryAndDistance() = check(
        "预计30分钟送达" to "Arrives in ~30 min",
        "预计35分钟" to "~35 min",
        "约3.2公里" to "~3.2 km",
        "约3.2km" to "~3.2 km",
        "距您约800米" to "~800 m away",
    )

    @Test fun reviewFixesPromotions() = check(
        "满¥30减¥5" to "¥5 off orders over ¥30",
        "满2件8折" to "20% off when you buy 2",
        "满2件减5元" to "¥5 off when you buy 2",
        "2件¥29.9" to "Any 2 for ¥29.9",
        "任选2件¥29.9" to "Any 2 for ¥29.9",
        "¥29.9/2件" to "Any 2 for ¥29.9",
        "买2送1" to "Buy 2 get 1 free",
        "买二送一" to "Buy 2 get 1 free",
        "到手¥19.9" to "Final price ¥19.9",
        "券后¥19.9起" to "From ¥19.9 after coupon",
        "预估到手价¥19.9" to "Est. final price ¥19.9",
        "加1元换购" to "Add ¥1 to get a deal item",
        "加¥1换购" to "Add ¥1 to get a deal item",
        "折扣商品8折起" to "Up to 20% off",
        "5块" to "¥5",
        "餐盒费¥2" to "Packaging fee ¥2",
        "门市价¥88" to "Regular price ¥88",
    )

    @Test fun reviewFixesHours() = check(
        "营业时间：周一至周五 10:00-22:00" to "Open Mon–Fri 10:00–22:00",
        "10:00-22:00营业" to "Open 10:00–22:00",
        "今日营业 10:00-22:00" to "Open 10:00–22:00",
        "营业到22:00" to "Open until 22:00",
        "10:00-次日02:00" to "10:00–02:00 (next day)",
        "明日10:00开始配送" to "Delivery starts tomorrow at 10:00",
        "周一至周日 10:00-22:00" to "Mon–Sun 10:00–22:00",
    )

    @Test fun reviewFixesSingulars() = check(
        "1人浏览" to "1 view",
        "3人浏览" to "3 views",
        "1人点赞" to "1 like",
        "1人想去" to "1 wants to go",
        "1人去过" to "1 has been",
        "1人关注" to "1 follower",
        "1人评价" to "1 rating",
        "2人评价" to "2 ratings",
    )

    @Test fun lookupForEmbeddedCategory() {
        val lookup = PatternTranslator.Lookup { if (it == "饮品") "Drinks" else null }
        assertEquals("#1 best seller in Drinks", PatternTranslator.translate("饮品热销第1名", lookup))
        assertNull(PatternTranslator.translate("未知类别热销第1名", lookup))
        val gift = PatternTranslator.Lookup { if (it == "可乐") "Coke" else null }
        assertEquals("Free Coke with orders over ¥50", PatternTranslator.translate("满50送可乐", gift))
    }

    @Test fun nonMatches() {
        assertNull(t("宫保鸡丁"))
        assertNull(t("1000+"))
        assertNull(t("¥20"))
        assertNull(t(""))
        assertNull(t("12分"))  // not a rating (> 5)
        assertNull(t("配送约30分"))
    }

    @Test fun helpers() {
        assertEquals("1,000+", PatternTranslator.count("1000", "", "+"))
        assertEquals("15,000", PatternTranslator.count("1.5", "万", ""))
        assertEquals("¥1,280.50", PatternTranslator.price("1280.50"))
        assertEquals("15", PatternTranslator.discountOff("8.5"))
        assertEquals("12", PatternTranslator.discountOff("88"))
        assertNull(PatternTranslator.discountOff("10"))
        assertEquals(25, PatternTranslator.cnInt("二十五"))
        assertEquals(12, PatternTranslator.cnInt("十二"))
        assertEquals(2, PatternTranslator.cnInt("两"))
        assertEquals("11th", PatternTranslator.ordinal(11))
        assertEquals("22nd", PatternTranslator.ordinal(22))
        assertEquals("¥20 ok", PatternTranslator.normalize("￥２０　ok"))
    }
}
