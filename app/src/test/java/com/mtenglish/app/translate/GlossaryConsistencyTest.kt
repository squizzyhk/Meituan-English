package com.mtenglish.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the real glossary + pattern rules together: common Meituan strings translate offline, house
 * terms are used consistently, and every example in Claude's system prompt agrees with what the
 * glossary/patterns produce for the same text (so one screen never shows two names for one thing).
 */
class GlossaryConsistencyTest {

    private val rules: RuleTranslator by lazy {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val dir = listOf(File(userDir, "src/main/assets/glossary"), File(userDir, "app/src/main/assets/glossary"))
            .first { it.isDirectory }
        val results = dir.listFiles { f -> f.isFile && f.name.endsWith(".tsv") }!!.sortedBy { it.name }
            .map { GlossaryParser.parse(it.readLines(Charsets.UTF_8), it.name) }
        RuleTranslator(GlossaryParser.merge(results))
    }

    private fun t(s: String) = rules.translate(s)

    @Test fun commonStoreAndCartStringsTranslate() {
        val missing = listOf(
            "去凑单", "凑单", "进店领券", "单点不送", "暂停接单", "商家休息中，暂停接单", "该商家已打烊", "品质联盟",
            "默认排序", "暂无评价", "商家已回复", "追加评价", "自提", "门店自提", "打包带走", "送货上门", "配送范围",
            "加购物车", "已选商品", "清除", "今日售罄", "该商品已售罄", "口味偏好", "日韩料理", "炸鸡汉堡", "汉堡披萨",
            "人均价高到低", "人均价低到高", "本店满减", "本店新客", "新店特惠", "特惠", "热销商品", "推荐商品",
            "招牌必点", "拼单中", "加入会员", "入会", "小时达", "30分钟达", "次日达", "准时", "超时",
            "由美团提供配送服务", "由商家提供配送服务", "集点", "口碑",
            "减", "首", "新", "折", "券", "免", "返", "特", "津",
            "味道赞", "分量足", "包装精美", "送餐及时", "很好吃", "好吃", "一般般", "不推荐", "性价比高", "味道好",
            "包装好", "送餐快", "服务热情", "环境优雅",
        ).filter { t(it) == null }
        assertTrue("Untranslated: $missing", missing.isEmpty())
    }

    @Test fun vouchersNeverRedPackets() {
        for (s in listOf("红包", "红包抵扣", "暂无可用红包", "使用红包", "新人红包", "¥5红包", "美团红包", "商家红包")) {
            val en = t(s) ?: error("$s untranslated")
            assertTrue("$s -> $en", en.contains("voucher", ignoreCase = true) && !en.contains("packet", ignoreCase = true))
        }
    }

    @Test fun consistentBadgeStyle() {
        assertEquals("¥20 min. order · Free delivery", t("起送¥20 免配送费"))
        assertEquals(t("0元配送"), t("免配送费"))
        assertEquals(t("第2份半价"), t("第二份半价"))
        assertEquals("With Photos (320)", t("有图(320)"))
        assertEquals("Newest", t("最新"))
        assertEquals("Total", t("实付"))
        assertEquals("Delivery time:", t("送达时间："))
        assertEquals("ASAP", t("立即送出"))
    }

    @Test fun spiceLevelsAreDistinct() {
        val levels = listOf("不辣", "微辣", "小辣", "中辣", "大辣", "特辣", "超辣", "变态辣")
        val en = levels.map { t(it) ?: error("$it untranslated") }
        assertEquals("Spice levels collide: ${levels.zip(en)}", en.size, en.toSet().size)
    }

    @Test fun contextSensitiveEntries() {
        assertEquals("Half & Half (Split Pot)", t("鸳鸯"))
        assertEquals("Yuenyeung (Coffee & Milk Tea)", t("鸳鸯奶茶"))
        assertEquals("¥5", t("5块"))
        assertEquals("Pre-order", t("预订"))
        assertEquals("Add to Cart", t("加购"))
    }

    /** Every "X → Y" line of the prompt's examples and house terms must agree with the offline rules. */
    @Test fun promptExamplesMatchGlossary() {
        val line = Regex("""^(?:[A-Z_]+: )?(\S.*?) → (.+)$""")
        val section = ClaudeTranslator.SYSTEM_PROMPT.substringAfter("# House terms").substringBefore("# Style guide") +
            ClaudeTranslator.SYSTEM_PROMPT.substringAfter("# Examples")
        val mismatches = section.lines().mapNotNull { l ->
            val m = line.matchEntire(l.trim()) ?: return@mapNotNull null
            val (zh, en) = m.destructured
            val offline = t(zh) ?: return@mapNotNull null
            if (offline == en) null else "$zh: prompt \"$en\" vs glossary \"$offline\""
        }
        assertTrue("Prompt examples disagree with the glossary:\n" + mismatches.joinToString("\n"), mismatches.isEmpty())
    }
}
