package com.mtenglish.app.translate

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Rule-based translator for the very common *dynamic* strings on Meituan / Dianping pages
 * (sales counts, prices, delivery fees, promos, times, distances, ratings, dates...).
 *
 * Pure Kotlin (no Android dependencies) so it is fully unit-testable on the JVM.
 * Each rule must match the WHOLE (normalized) string; composite strings such as
 * "4.8分 月售1000+ 人均¥35" are split into segments by [RuleTranslator].
 */
object PatternTranslator {

    /** Optional dictionary lookup (glossary) used for embedded terms such as "饮品热销第1名". */
    fun interface Lookup {
        fun find(chinese: String): String?
    }

    private val NO_LOOKUP = Lookup { null }

    // ---- Building blocks (each documents how many capture groups it adds) ----
    /** 1 group: a number, optionally with thousands separators / decimals. */
    private const val NUM = """(\d[\d,]*(?:\.\d+)?)"""
    /** 3 groups: number, magnitude unit (万/千/w/k), plus sign. */
    private const val CNT = """(\d[\d,]*(?:\.\d+)?)\s*(万|千|w|W|k|K)?\s*(\+)?"""
    /** 1 group: price amount, with optional ¥ prefix and 元 suffix. */
    private const val PRICE = """¥?\s*(\d[\d,]*(?:\.\d+)?)\s*元?"""
    /** 1 group: clock time HH:MM. */
    private const val TIME = """(\d{1,2}:\d{2})"""
    /** 2 groups: a number or a range "30-40". */
    private const val RANGE = """(\d+(?:\.\d+)?)(?:\s*[-~至到–—]\s*(\d+(?:\.\d+)?))?"""
    /** 1 group: Arabic or Chinese numeral. */
    private const val CN = """([一二两三四五六七八九十\d]+)"""
    /** 1 group: relative day word. */
    private const val DAY = """(今天|明天|后天|昨天|前天|今日|明日|昨日)"""
    /** 1 group: weekday character after 周/星期/礼拜. */
    private const val WD = """(?:周|星期|礼拜)([一二三四五六日天])"""

    private class Rule(pattern: String, val fn: (g: List<String>, lookup: Lookup) -> String?) {
        val regex = Regex(pattern)
    }

    private val rules = ArrayList<Rule>()
    private fun rule(pattern: String, fn: (g: List<String>, lookup: Lookup) -> String?) {
        rules += Rule(pattern, fn)
    }

    // ---- Vocabulary used by rules ----
    private val PRICE_LABELS = linkedMapOf(
        "合计" to "Total", "总计" to "Total", "共计" to "Total", "总价" to "Total", "订单总价" to "Order total",
        "小计" to "Subtotal", "实付" to "Total", "实付款" to "Paid", "实付金额" to "Paid", "已付" to "Paid",
        "待支付" to "Amount due", "需支付" to "To pay", "需付" to "To pay", "还需支付" to "To pay", "应付" to "Amount due",
        "应付金额" to "Amount due", "支付金额" to "Payment", "订单金额" to "Order amount",
        "包装费" to "Packaging fee", "打包费" to "Packaging fee", "餐盒费" to "Packaging fee", "餐具费" to "Cutlery fee",
        "原价" to "Was", "门市价" to "Regular price", "现价" to "Now", "售价" to "Price", "价格" to "Price",
        "单价" to "Unit price", "会员价" to "Member price", "特价" to "Special", "活动价" to "Deal price",
        "折后价" to "Sale price", "到手价" to "Final price", "到手" to "Final price",
        "预估到手价" to "Est. final price", "预估到手" to "Est. final price", "预估价" to "Est. price", "秒杀价" to "Flash sale", "拼团价" to "Group price",
        "团购价" to "Deal price", "优惠价" to "Discounted", "余额" to "Balance", "押金" to "Deposit",
        "商品金额" to "Items", "商品总额" to "Items total", "菜品金额" to "Items", "优惠金额" to "Discount",
        "红包抵扣" to "Voucher applied", "抵扣" to "Deducted", "退款金额" to "Refund", "退款" to "Refund",
        "小费" to "Tip", "加价" to "Extra",
    )
    private val SCORE_LABELS = linkedMapOf(
        "口味" to "Taste", "味道" to "Taste", "环境" to "Ambience", "服务" to "Service", "食材" to "Ingredients",
        "性价比" to "Value", "包装" to "Packaging", "分量" to "Portion", "效果" to "Results",
        "卫生" to "Hygiene", "设施" to "Facilities", "位置" to "Location", "技师" to "Staff",
    )
    /** Per-unit price suffix: ¥N/份 -> "¥N/serving". */
    private val PER_UNIT = linkedMapOf(
        "份" to "serving", "个" to "pc", "杯" to "cup", "碗" to "bowl", "位" to "person", "人" to "person",
        "盒" to "box", "瓶" to "bottle", "件" to "item", "只" to "pc", "串" to "skewer", "例" to "portion",
        "根" to "pc", "块" to "piece", "张" to "pc", "次" to "visit", "晚" to "night", "小时" to "hr",
        "天" to "day", "月" to "month", "年" to "year", "公斤" to "kg", "千克" to "kg", "kg" to "kg",
        "克" to "g", "g" to "g", "两" to "50g", "斤" to "500g", "间" to "room", "片" to "slice", "套" to "set",
        "桌" to "table", "锅" to "pot", "斤装" to "500g",
    )
    /** Quantity units: singular / plural. */
    private val QTY_UNITS = linkedMapOf(
        "份" to ("serving" to "servings"), "个" to ("pc" to "pcs"), "杯" to ("cup" to "cups"),
        "碗" to ("bowl" to "bowls"), "盒" to ("box" to "boxes"), "瓶" to ("bottle" to "bottles"),
        "件" to ("item" to "items"), "只" to ("pc" to "pcs"), "串" to ("skewer" to "skewers"),
        "根" to ("pc" to "pcs"), "片" to ("slice" to "slices"),
        "袋" to ("bag" to "bags"), "罐" to ("can" to "cans"), "听" to ("can" to "cans"), "包" to ("pack" to "packs"),
        "条" to ("pc" to "pcs"), "张" to ("pc" to "pcs"), "位" to ("person" to "people"),
        "人" to ("person" to "people"), "次" to ("time" to "times"), "晚" to ("night" to "nights"),
        "间" to ("room" to "rooms"), "款" to ("option" to "options"), "种" to ("type" to "types"),
        "道" to ("dish" to "dishes"), "套" to ("set" to "sets"), "桌" to ("table" to "tables"),
        "颗" to ("pc" to "pcs"), "粒" to ("pc" to "pcs"), "支" to ("pc" to "pcs"), "盘" to ("plate" to "plates"),
        "锅" to ("pot" to "pots"), "天" to ("day" to "days"), "笼" to ("basket" to "baskets"), "打" to ("dozen" to "dozen"),
    )
    /** "N人<verb>": singular / plural format (singular used when N is exactly 1). */
    private val PEOPLE_VERBS = linkedMapOf(
        "付款" to same("%s bought"), "已付款" to same("%s bought"), "已买" to same("%s bought"),
        "买过" to same("%s bought"), "购买" to same("%s bought"), "已购" to same("%s bought"),
        "已购买" to same("%s bought"), "已下单" to same("%s ordered"), "下单" to same("%s ordered"),
        "已拼" to same("%s joined"), "想去" to ("%s wants to go" to "%s want to go"),
        "去过" to ("%s has been" to "%s have been"), "收藏" to same("%s saved"),
        "浏览" to ("%s view" to "%s views"), "看过" to same("%s viewed"), "已领" to same("%s claimed"),
        "领取" to same("%s claimed"), "推荐" to same("Recommended by %s"),
        "关注" to ("%s follower" to "%s followers"), "点赞" to ("%s like" to "%s likes"),
        "吃过" to ("%s has tried" to "%s have tried"), "在吃" to same("%s eating now"),
        "评价" to ("%s rating" to "%s ratings"), "评分" to ("%s rating" to "%s ratings"),
        "打分" to ("%s rating" to "%s ratings"), "预订" to same("%s booked"), "已预订" to same("%s booked"),
        "消费" to ("%s visit" to "%s visits"), "到店" to ("%s visit" to "%s visits"), "使用" to same("%s used"),
        "已使用" to same("%s used"), "参与" to same("%s joined"),
    )
    private val REVIEW_FILTERS = linkedMapOf(
        "全部" to "All", "好评" to "Positive", "差评" to "Negative", "中评" to "Neutral", "有图" to "With Photos",
        "晒图" to "With Photos", "带图" to "With Photos", "有图评价" to "With Photos", "最新" to "Newest",
        "追评" to "Follow-ups", "图片" to "Photos", "视频" to "Videos", "评价" to "Reviews", "评论" to "Comments",
        "点评" to "Reviews", "回复" to "Replies", "推荐菜" to "Popular dishes", "相册" to "Photos",
    )
    private val COUPON_WORDS = linkedMapOf(
        "红包" to "voucher", "神券" to "super coupon", "会员红包" to "member voucher", "券" to "coupon",
        "优惠券" to "coupon", "代金券" to "voucher", "抵用券" to "coupon", "满减券" to "coupon",
        "店铺红包" to "store voucher", "商家券" to "store coupon", "无门槛券" to "no-minimum coupon",
    )

    private fun same(format: String) = format to format

    private val MONTHS_SHORT = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val MONTHS_FULL = arrayOf(
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December",
    )

    /** Regex alternation of literal keys (keys contain no regex metacharacters), longest first. */
    private fun alt(keys: Collection<String>) = keys.sortedByDescending { it.length }.joinToString("|")

    init {
        val priceLabels = alt(PRICE_LABELS.keys)
        val scoreLabels = alt(SCORE_LABELS.keys)
        val perUnits = alt(PER_UNIT.keys)
        val qtyUnits = alt(QTY_UNITS.keys)
        val peopleVerbs = alt(PEOPLE_VERBS.keys)
        val reviewFilters = alt(REVIEW_FILTERS.keys)
        val couponWords = alt(COUPON_WORDS.keys)

        // ================= Sales volume =================
        rule("""(?:月售|月销量?|月销售)\s*:?\s*$CNT\s*(?:份|单|件|笔)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold/month") }
        rule("""(?:已售|已卖|已销售?|累计售出|已售出)\s*:?\s*$CNT\s*(?:份|单|件|笔|张)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold") }
        rule("""(?:总销量|销量|销售量)\s*:?\s*$CNT\s*(?:份|单|件|笔)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold") }
        rule("""年售\s*:?\s*$CNT\s*(?:份|单|件)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold/year") }
        rule("""半年售\s*:?\s*$CNT\s*(?:份|单|件)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold in 6 months") }
        rule("""周售\s*:?\s*$CNT\s*(?:份|单|件)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold/week") }
        rule("""日售\s*:?\s*$CNT\s*(?:份|单|件)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" sold/day") }

        // ================= Rankings =================
        rule("""销量第\s*$CN\s*名?""") { g, _ -> cnInt(g[1])?.let { "#$it best seller" } }
        rule("""(.{0,12}?)(?:热销|热卖|畅销)榜?\s*(?:第|TOP|Top|top|NO\.?|No\.?)\s*$CN\s*名?""") { g, l ->
            val n = cnInt(g[2]) ?: return@rule null
            withCategory("#$n best seller", g[1], l)
        }
        rule("""(.{0,12}?)(?:好评|口碑)榜?\s*(?:第|TOP|Top|top|NO\.?|No\.?)\s*$CN\s*名?""") { g, l ->
            val n = cnInt(g[2]) ?: return@rule null
            withCategory("#$n top rated", g[1], l)
        }
        rule("""(.{0,12}?)(?:人气|回头客)榜?\s*(?:第|TOP|Top|top|NO\.?|No\.?)\s*$CN\s*名?""") { g, l ->
            val n = cnInt(g[2]) ?: return@rule null
            withCategory("#$n most popular", g[1], l)
        }
        rule("""排名第?\s*$CN\s*名?""") { g, _ -> cnInt(g[1])?.let { "Ranked #$it" } }
        rule("""第\s*$CN\s*名""") { g, _ -> cnInt(g[1])?.let { "#$it" } }
        rule("""第\s*$CN\s*(?:份|件|杯|个)\s*半价""") { g, _ -> cnInt(g[1])?.let { "${ordinal(it)} item half price" } }
        rule("""第\s*$CN\s*(?:份|件|杯|个)\s*(\d+(?:\.\d+)?)\s*折""") { g, _ ->
            val n = cnInt(g[1]) ?: return@rule null
            discountOff(g[2])?.let { "${ordinal(n)} item $it% off" }
        }
        rule("""第\s*$CN\s*(?:份|件|杯|个)\s*(?:免费|0元|免单)""") { g, _ -> cnInt(g[1])?.let { "${ordinal(it)} item free" } }
        rule("""第\s*$CN\s*(?:份|件|杯|个)\s*$PRICE""") { g, _ -> cnInt(g[1])?.let { "${ordinal(it)} item ${price(g[2])}" } }

        // ================= Minimum order / delivery fee =================
        rule("""(?:起送价?|最低起送|起送金额)\s*:?\s*$PRICE""") { g, _ -> "${price(g[1])} min. order" }
        rule("""$PRICE\s*起送""") { g, _ -> "${price(g[1])} min. order" }
        rule("""(?:还差|差|再买|再点|还需|再凑)\s*$PRICE\s*(?:即可|可|就能)?起送""") { g, _ -> "Add ${price(g[1])} more to order" }
        rule("""(?:还差|差|再买|再点|再凑|还需)\s*$PRICE\s*(?:即可|可|立|就能)?(?:享)?减\s*$PRICE""") { g, _ ->
            "Add ${price(g[1])} more to save ${price(g[2])}"
        }
        rule("""(?:还差|差|再买|再点|再凑|还需)\s*$PRICE\s*(?:即可|可|就能)?(?:享)?(?:免配送费|免运费|包邮|免配送)""") { g, _ ->
            "Add ${price(g[1])} more for free delivery"
        }
        rule("""免配送费|免费配送|配送费全免|免运费|0元配送|免配送|零配送费""") { _, _ -> "Free delivery" }
        rule("""包邮|免邮""") { _, _ -> "Free shipping" }
        rule("""(?:配送费|运费|配送|送费)\s*:?\s*(?:¥\s*0(?:\.0+)?|0(?:\.0+)?\s*元)""") { _, _ -> "Free delivery" }
        rule("""(?:另需|另付|另加)配送费\s*:?\s*$PRICE""") { g, _ -> "+${price(g[1])} delivery" }
        rule("""(?:减配送费|配送费立?减|配送费优惠|配送费减免|立减配送费)\s*:?\s*$PRICE""") { g, _ -> "${price(g[1])} off delivery" }
        rule("""(?:配送费|运费|配送|送费|跑腿费)\s*:?\s*(约)?\s*$PRICE\s*(起)?""") { g, _ ->
            val p = price(g[2])
            when {
                g[3].isNotEmpty() -> "Delivery from $p"
                g[1].isNotEmpty() -> "~$p delivery"
                else -> "$p delivery"
            }
        }
        rule("""满\s*¥?\s*$NUM\s*元?\s*(?:免配送费|免运费|包邮|免配送|免费配送)""") { g, _ -> "Free delivery over ${price(g[1])}" }
        rule("""人均\s*(?:消费)?\s*:?\s*(约)?\s*$PRICE""") { g, _ -> (if (g[1].isNotEmpty()) "~" else "") + "${price(g[2])} per person" }

        // ================= Promotions =================
        rule("""每满\s*¥?\s*$NUM\s*元?\s*减\s*¥?\s*$NUM\s*元?""") { g, _ -> "${price(g[2])} off every ${price(g[1])}" }
        rule("""满\s*¥?\s*$NUM\s*元?\s*(?:立)?减\s*¥?\s*$NUM\s*元?""") { g, _ -> "${price(g[2])} off orders over ${price(g[1])}" }
        rule("""(?:满\s*¥?\s*\d[\d,]*(?:\.\d+)?\s*元?\s*减\s*¥?\s*\d[\d,]*(?:\.\d+)?\s*元?\s*[,;、/|]?\s*){2,}""") { g, _ ->
            Regex("""满\s*¥?\s*(\d[\d,]*(?:\.\d+)?)\s*元?\s*减\s*¥?\s*(\d[\d,]*(?:\.\d+)?)""").findAll(g[0])
                .joinToString(", ") { m -> "${price(m.groupValues[2])} off orders over ${price(m.groupValues[1])}" }
        }
        rule("""满\s*$CN\s*(?:件|份|杯)\s*(?:享|打|立享)?\s*(\d+(?:\.\d+)?)\s*折""") { g, _ ->
            val n = cnInt(g[1]) ?: return@rule null
            discountOff(g[2])?.let { "$it% off when you buy $n" }
        }
        rule("""满\s*$CN\s*(?:件|份|杯)\s*(?:立)?减\s*$PRICE""") { g, _ ->
            val n = cnInt(g[1]) ?: return@rule null
            "${price(g[2])} off when you buy $n"
        }
        rule("""满\s*¥?\s*$NUM\s*元?\s*(?:享|打|立享)?\s*(\d+(?:\.\d+)?)\s*折""") { g, _ ->
            discountOff(g[2])?.let { "$it% off orders over ${price(g[1])}" }
        }
        rule("""满\s*¥?\s*$NUM\s*元?\s*(?:可用|使用|可使用)""") { g, _ -> "Min. spend ${price(g[1])}" }
        rule("""(?:任选|任意)?\s*$CN\s*(?:件|份|杯)\s*(?:共|仅|只要)?\s*¥\s*(\d[\d,]*(?:\.\d+)?)\s*元?""") { g, _ ->
            val n = cnInt(g[1])?.takeIf { it > 1 } ?: return@rule null
            "Any $n for ${price(g[2])}"
        }
        rule("""(?:任选|任意)\s*$CN\s*(?:件|份|杯)\s*(?:共|仅|只要)?\s*(\d[\d,]*(?:\.\d+)?)\s*元""") { g, _ ->
            val n = cnInt(g[1])?.takeIf { it > 1 } ?: return@rule null
            "Any $n for ${price(g[2])}"
        }
        rule("""$PRICE\s*/\s*$CN\s*(?:件|份|杯)""") { g, _ ->
            val n = cnInt(g[2]) ?: return@rule null
            if (n == 1) "${price(g[1])}/item" else "Any $n for ${price(g[1])}"
        }
        rule("""买\s*$CN\s*(?:件|份|杯|个)?\s*送\s*$CN\s*(?:件|份|杯|个)?""") { g, _ ->
            val a = cnInt(g[1]) ?: return@rule null
            val b = cnInt(g[2]) ?: return@rule null
            "Buy $a get $b free"
        }
        rule("""(?:加|加价)\s*$PRICE\s*(?:即可)?换购""") { g, _ -> "Add ${price(g[1])} to get a deal item" }
        rule("""满\s*¥?\s*$NUM\s*元?\s*(?:送|赠|赠送|即送)\s*(.+)""") { g, l ->
            l.find(g[2].trim())?.let { "Free $it with orders over ${price(g[1])}" }
        }
        rule("""$PRICE\s*无门槛(?:券|红包|优惠券)?""") { g, _ -> "${price(g[1])} off, no minimum" }
        rule("""新(?:客|人|用户)(?:专享)?(?:立减|减|优惠|首单减|首单立减)\s*$PRICE""") { g, _ -> "New customers: ${price(g[1])} off" }
        rule("""新(?:客|人|用户)(?:专享)?\s*$PRICE\s*(?:红包|券|优惠券)""") { g, _ -> "New customers: ${price(g[1])} voucher" }
        rule("""首单(?:立减|减|优惠|立享)\s*$PRICE""") { g, _ -> "${price(g[1])} off first order" }
        rule("""(?:下单|在线支付|门店新客|进店)?(?:立减|已减|减|直减)\s*$PRICE""") { g, _ -> "${price(g[1])} off" }
        rule("""(?:已优惠|共优惠|已省|共省|已节省|节省了|优惠了|共节省|本单已省|为您节省|已为您节省)\s*:?\s*$PRICE""") { g, _ -> "${price(g[1])} saved" }
        rule("""(?:立省|省|节省|可省|最高省|优惠)\s*$PRICE""") { g, _ -> "Save ${price(g[1])}" }
        rule("""$PRICE\s*($couponWords)""") { g, _ -> "${price(g[1])} ${COUPON_WORDS[g[2]]}" }
        rule("""$PRICE\s*代\s*$PRICE\s*(?:代金券|券)?""") { g, _ -> "${price(g[1])} for a ${price(g[2])} voucher" }
        rule("""券后\s*(?:价)?\s*:?\s*$PRICE\s*(起)?""") { g, _ ->
            if (g[2].isNotEmpty()) "From ${price(g[1])} after coupon" else "${price(g[1])} after coupon"
        }
        rule("""(?:低至|最低|最低至)\s*(\d+(?:\.\d+)?)\s*折""") { g, _ -> discountOff(g[1])?.let { "Up to $it% off" } }
        rule("""(?:折扣商品|折扣|特价商品|全场|全店|店内)?\s*(\d+(?:\.\d+)?)\s*折起""") { g, _ -> discountOff(g[1])?.let { "Up to $it% off" } }
        rule("""(全场|全店|店内|会员|限时|新客)?\s*(\d+(?:\.\d+)?)\s*折(?:优惠)?""") { g, _ ->
            val off = discountOff(g[2]) ?: return@rule null
            when (g[1]) {
                "全场", "全店", "店内" -> "$off% off storewide"
                "会员" -> "Members: $off% off"
                "限时" -> "$off% off, limited time"
                "新客" -> "New customers: $off% off"
                else -> "$off% off"
            }
        }

        // ================= Limits / stock =================
        rule("""(每单|每人|每个用户|每桌|每天|每日)?限购\s*(\d+)\s*(?:份|件|个|次|张)?""") { g, _ ->
            when (g[1]) {
                "每单", "每桌" -> "Limit ${g[2]} per order"
                "每人", "每个用户" -> "Limit ${g[2]} per person"
                "每天", "每日" -> "Limit ${g[2]} per day"
                else -> "Limit ${g[2]}"
            }
        }
        rule("""(\d+)\s*(?:份|件|个|杯)\s*起(?:购|售|订|点|送)""") { g, _ -> "Min. ${g[1]}" }
        rule("""仅剩\s*(\d+)\s*(?:份|件|个|张|间|位)?""") { g, _ -> "Only ${g[1]} left" }
        rule("""(?:剩余|还剩|剩)\s*(\d+)\s*(?:份|件|个|张|间|位)?""") { g, _ -> "${g[1]} left" }
        rule("""库存\s*:?\s*(\d+)\s*(?:份|件|个)?""") { g, _ -> "${g[1]} in stock" }
        rule("""已抢\s*(\d+(?:\.\d+)?)\s*%""") { g, _ -> "${g[1]}% claimed" }
        rule("""已抢\s*$CNT\s*(?:份|件|张)?""") { g, _ -> count(g[1], g[2], g[3])?.plus(" claimed") }

        // ================= Prices =================
        rule("""($priceLabels)\s*:?\s*$PRICE\s*(起)?""") { g, _ ->
            val label = PRICE_LABELS[g[1]]!!
            if (g[3].isNotEmpty()) "$label from ${price(g[2])}" else "$label ${price(g[2])}"
        }
        rule("""$PRICE\s*起""") { g, _ -> "From ${price(g[1])}" }
        rule("""$PRICE\s*/\s*($perUnits)""") { g, _ -> "${price(g[1])}/${PER_UNIT[g[2]]}" }
        rule("""(\d[\d,]*(?:\.\d+)?)\s*(?:元|块钱|元钱|块)""") { g, _ -> price(g[1]) }

        // ================= Delivery / arrival times =================
        rule("""(?:预计|预计在|大约|约)\s*$DAY?\s*$TIME\s*(?:前|之前|左右)?\s*送达""") { g, _ -> "Arrives ${dayLower(g[1])}by ${g[2]}" }
        rule("""(?:预计送达(?:时间)?|送达时间|期望送达(?:时间)?|预计到达)\s*:?\s*$DAY?\s*$TIME""") { g, _ ->
            "Est. arrival ${dayLower(g[1])}${g[2]}"
        }
        rule("""$DAY?\s*$TIME\s*(?:前|之前)\s*送达""") { g, _ -> "Arrives ${dayLower(g[1])}by ${g[2]}" }
        rule("""$DAY?\s*$TIME\s*[-~至]\s*$TIME\s*送达""") { g, _ -> "Arrives ${dayLower(g[1])}${g[2]}–${g[3]}" }
        rule("""$DAY?\s*$TIME\s*(?:左右)?\s*送达""") { g, _ ->
            if (g[1].isEmpty()) "Arrives at ${g[2]}" else "Arrives ${dayLower(g[1])}${g[2]}"
        }
        rule("""$DAY\s*$TIME(?:\s*[-~至]\s*$TIME)?""") { g, _ ->
            dayWord(g[1]) + " " + g[2] + (if (g[3].isNotEmpty()) "–${g[3]}" else "")
        }
        rule("""(预计约|预计大约|预计|约|大约)?\s*$RANGE\s*分钟\s*(内|以内)?\s*送达""") { g, _ ->
            val r = range(g[2], g[3])
            when {
                g[4].isNotEmpty() -> "Delivered within $r min"
                g[1].isNotEmpty() -> "Arrives in ~$r min"
                else -> "Arrives in $r min"
            }
        }
        rule("""(?:配送|送达|配送时间|送达时间|配送时长|平均配送|平均送达)\s*:?\s*(约|大约)?\s*$RANGE\s*分钟""") { g, _ ->
            (if (g[1].isNotEmpty()) "~" else "") + "${range(g[2], g[3])} min delivery"
        }
        rule("""(步行|驾车|开车|骑行|打车)\s*(约)?\s*$RANGE\s*分钟""") { g, _ ->
            val mode = when (g[1]) { "步行" -> "walk"; "骑行" -> "ride"; else -> "drive" }
            (if (g[2].isNotEmpty()) "~" else "") + "${range(g[3], g[4])} min $mode"
        }
        rule("""(预计约|预计大约|预计|约|大约)?\s*$RANGE\s*分钟\s*(内|以内|后)?""") { g, _ ->
            val r = range(g[2], g[3])
            when (g[4]) {
                "内", "以内" -> "Within $r min"
                "后" -> "In $r min"
                else -> (if (g[1].isNotEmpty()) "~" else "") + "$r min"
            }
        }
        rule("""(约|大约)?\s*(\d+(?:\.\d+)?)\s*个?(?:半)?小时\s*(?:(\d+)\s*分钟?)?\s*(内|以内|后)?""") { g, _ ->
            val h = g[2]
            val half = g[0].contains("半小时") || g[0].contains("个半")
            val hours = if (half) (h.toBigDecimal() + BigDecimal("0.5")).stripTrailingZeros().toPlainString() else h
            var s = "$hours ${if (hours == "1") "hr" else "hrs"}"
            if (g[3].isNotEmpty()) s += " ${g[3]} min"
            when (g[4]) {
                "内", "以内" -> "Within $s"
                "后" -> "In $s"
                else -> (if (g[1].isNotEmpty()) "~" else "") + s
            }
        }
        rule("""半小时(内|以内|后)?""") { g, _ ->
            when (g[1]) { "内", "以内" -> "Within 30 min"; "后" -> "In 30 min"; else -> "30 min" }
        }
        rule("""(\d+)\s*秒""") { g, _ -> "${g[1]} sec" }

        // ================= Relative time =================
        rule("""刚刚|刚才|刚刚更新""") { _, _ -> "Just now" }
        rule("""(\d+)\s*秒(?:钟)?前""") { g, _ -> plural(g[1], "second", "seconds") + " ago" }
        rule("""(\d+)\s*分钟前""") { g, _ -> plural(g[1], "minute", "minutes") + " ago" }
        rule("""(\d+)\s*(?:个)?小时前""") { g, _ -> plural(g[1], "hour", "hours") + " ago" }
        rule("""(\d+)\s*天前""") { g, _ -> plural(g[1], "day", "days") + " ago" }
        rule("""(\d+)\s*(?:周|星期)前""") { g, _ -> plural(g[1], "week", "weeks") + " ago" }
        rule("""(\d+)\s*(?:个)?月前""") { g, _ -> plural(g[1], "month", "months") + " ago" }
        rule("""(\d+)\s*年前""") { g, _ -> plural(g[1], "year", "years") + " ago" }
        rule("""半小时前""") { _, _ -> "30 minutes ago" }
        rule(DAY) { g, _ -> dayWord(g[1]) }

        // ================= Business hours =================
        val tr = """\d{1,2}:\d{2}\s*[-~至到–—]\s*(?:次日|第二天)?\s*\d{1,2}:\d{2}"""
        val trs = """($tr(?:\s*[,;、/]?\s*$tr)*)"""
        val wdRange = """(?:$WD\s*[至到\-~–—]\s*(?:周|星期|礼拜)?([一二三四五六日天]))?"""
        rule("""(?:营业时间|营业中|营业时段|营业时间段|今日营业|今天营业|营业)\s*:?\s*$wdRange\s*$trs""") { g, _ ->
            val days = if (g[1].isNotEmpty()) "${weekday(g[1], true)}–${weekday(g[2], true)} " else ""
            "Open $days" + hourRanges(g[3])
        }
        rule("""$trs\s*营业""") { g, _ -> "Open " + hourRanges(g[1]) }
        rule("""$WD\s*[至到\-~–—]\s*(?:周|星期|礼拜)?([一二三四五六日天])\s*:?\s*$trs""") { g, _ ->
            "${weekday(g[1], true)}–${weekday(g[2], true)} " + hourRanges(g[3])
        }
        rule("""营业(?:到|至)\s*(次日)?\s*$TIME""") { g, _ -> "Open until ${g[2]}" + if (g[1].isNotEmpty()) " (next day)" else "" }
        rule("""(\d{1,2}:\d{2}\s*[-~至到–—]\s*(?:次日|第二天)\s*\d{1,2}:\d{2})""") { g, _ -> hourRanges(g[1]) }
        rule("""(?:全天|24小时|24h)\s*营业""") { _, _ -> "Open 24 hours" }
        rule("""$DAY?\s*$TIME\s*(?:开始)?(?:营业|开业|开门|开店)""") { g, _ ->
            "Opens ${dayLower(g[1])}at ${g[2]}"
        }
        rule("""$DAY?\s*$TIME\s*(?:后)?(?:打烊|关门|停止营业|闭店|结束营业)""") { g, _ -> "Closes ${dayLower(g[1])}at ${g[2]}" }
        rule("""$DAY?\s*$TIME\s*(?:后)?(?:可)?(?:开始)?(?:配送|送餐|接单|送出)""") { g, _ ->
            "Delivery starts ${dayLower(g[1])}at ${g[2]}"
        }
        rule("""$TIME\s*后?可预订""") { g, _ -> "Pre-order for after ${g[1]}" }

        // ================= Distance =================
        rule("""(距离?|距您|距你|离您|离你|距离您)?\s*(约|大约)?\s*(<|小于|不到)?\s*(\d+(?:\.\d+)?)\s*(km|KM|Km|公里|千米)""") { g, _ ->
            distance(g[1], g[2], g[3], g[4], "km")
        }
        rule("""(距离?|距您|距你|离您|离你|距离您)?\s*(约|大约)?\s*(<|小于|不到)?\s*(\d+(?:\.\d+)?)\s*(m|M|米)""") { g, _ ->
            distance(g[1], g[2], g[3], g[4], "m")
        }

        // ================= Ratings =================
        rule("""(?:评分|商家评分|店铺评分|综合评分|总评分|星级评分)\s*:?\s*(\d(?:\.\d+)?)\s*分?""") { g, _ -> "Rated ${g[1]}" }
        rule("""($scoreLabels)\s*:?\s*(\d(?:\.\d+)?)\s*分?""") { g, _ -> "${SCORE_LABELS[g[1]]} ${g[2]}" }
        rule("""(\d(?:\.\d)?)\s*分""") { g, _ ->
            g[1].takeIf { (it.toBigDecimalOrNull() ?: BigDecimal.TEN) <= BigDecimal(5) }?.let { "★ $it" }
        }
        rule("""(\d(?:\.\d)?)\s*(?:颗)?星""") { g, _ -> if (g[1] == "1") "1 star" else "${g[1]} stars" }
        rule("""$CN\s*星级\s*(酒店)?""") { g, _ -> cnInt(g[1])?.let { "$it-star" + if (g[2].isNotEmpty()) " hotel" else "" } }

        // ================= Reviews / social proof =================
        rule("""(?:共\s*)?$CNT\s*条\s*(评价|评论|点评|回复|留言|笔记)""") { g, _ ->
            val c = count(g[1], g[2], g[3]) ?: return@rule null
            val one = c == "1"
            c + " " + when (g[4]) {
                "回复" -> if (one) "reply" else "replies"
                "评论", "留言" -> if (one) "comment" else "comments"
                "笔记" -> if (one) "post" else "posts"
                else -> if (one) "review" else "reviews"
            }
        }
        rule("""(好评率|好评度|满意度|推荐率|准时率)\s*:?\s*(\d+(?:\.\d+)?)\s*%""") { g, _ ->
            when (g[1]) {
                "满意度" -> "${g[2]}% satisfied"
                "推荐率" -> "${g[2]}% recommend"
                "准时率" -> "${g[2]}% on time"
                else -> "${g[2]}% positive"
            }
        }
        rule("""(\d+(?:\.\d+)?)\s*%\s*(?:好评|好评率)""") { g, _ -> "${g[1]}% positive" }
        rule("""(\d+(?:\.\d+)?)\s*%\s*(?:的人)?(?:推荐|回购)""") { g, _ -> "${g[1]}% recommend" }
        rule("""($reviewFilters)\s*\(\s*$CNT\s*\)""") { g, _ -> count(g[2], g[3], g[4])?.let { "${REVIEW_FILTERS[g[1]]} ($it)" } }
        rule("""($reviewFilters)\s*$CNT""") { g, _ -> count(g[2], g[3], g[4])?.let { "${REVIEW_FILTERS[g[1]]} ($it)" } }
        rule("""$CNT\s*人\s*($peopleVerbs)""") { g, _ ->
            count(g[1], g[2], g[3])?.let { c ->
                val (one, many) = PEOPLE_VERBS[g[4]]!!
                (if (c == "1") one else many).format(c)
            }
        }
        rule("""$CNT\s*(?:次)?(?:浏览|阅读|播放)""") { g, _ -> count(g[1], g[2], g[3])?.let { if (it == "1") "1 view" else "$it views" } }
        rule("""$CNT\s*(?:个)?(?:赞|点赞)""") { g, _ -> count(g[1], g[2], g[3])?.let { if (it == "1") "1 like" else "$it likes" } }
        rule("""$CNT\s*人""") { g, _ -> count(g[1], g[2], g[3])?.let { if (it == "1") "1 person" else "$it people" } }

        // ================= Quantities / items =================
        rule("""(?:共|合计|总共)\s*(\d+)\s*(?:件|份|个|样)\s*(?:商品|菜品|宝贝)?""") { g, _ -> plural(g[1], "item", "items") }
        rule("""(\d+)\s*(?:件|样)\s*(?:商品|菜品|宝贝)""") { g, _ -> plural(g[1], "item", "items") }
        rule("""已选\s*:?\s*(\d+)\s*(?:件|份|个|样)?""") { g, _ -> "${g[1]} selected" }
        rule("""[xX×*]\s*(\d+)\s*(?:份|个|件|杯|盒|瓶|碗)?""") { g, _ -> "×${g[1]}" }
        rule("""(\d+)\s*[-~至到]\s*(\d+)\s*人\s*(餐|套餐|份|食)?""") { g, _ ->
            val r = "${g[1]}–${g[2]}"
            when (g[3]) { "餐", "套餐" -> "Meal for $r"; "份", "食" -> "Serves $r"; else -> "$r people" }
        }
        rule("""(单|双|$CN)\s*人\s*(?:套)?餐""") { g, _ ->
            val n = when (g[1]) { "单" -> 1; "双" -> 2; else -> cnInt(g[1]) } ?: return@rule null
            "Meal for $n"
        }
        rule("""$CN\s*人(?:份|食)""") { g, _ -> cnInt(g[1])?.let { "Serves $it" } }
        rule("""(\d+(?:\.\d+)?)\s*(斤|两|克|千克|公斤|毫升|升|寸|英寸)""") { g, _ ->
            val v = g[1].toBigDecimalOrNull() ?: return@rule null
            when (g[2]) {
                "斤" -> grams(v.multiply(BigDecimal(500)))
                "两" -> grams(v.multiply(BigDecimal(50)))
                "克" -> "${g[1]}g"
                "千克", "公斤" -> "${g[1]}kg"
                "毫升" -> "${g[1]}ml"
                "升" -> "${g[1]}L"
                else -> "${g[1]}-inch"
            }
        }
        rule("""(\d+)\s*($qtyUnits)""") { g, _ ->
            val (one, many) = QTY_UNITS[g[2]] ?: return@rule null
            plural(g[1], one, many)
        }
        rule("""(\d+)\s*家\s*(?:门店|店|分店|商家)""") { g, _ -> plural(g[1], "location", "locations") }

        // ================= Validity / countdown =================
        rule("""(\d+)\s*天内有效""") { g, _ -> "Valid for " + plural(g[1], "day", "days") }
        rule("""有效期\s*:?\s*(\d+)\s*天""") { g, _ -> "Valid for " + plural(g[1], "day", "days") }
        rule("""(\d+)\s*天后(?:过期|到期|失效|结束)""") { g, _ -> "Expires in " + plural(g[1], "day", "days") }
        rule("""(?:距(?:离)?(?:活动)?结束|距结束还有|距离结束还有|倒计时|仅剩|剩余|还剩)\s*:?\s*(\d{1,2}:\d{2}(?::\d{2})?)""") { g, _ -> "Ends in ${g[1]}" }
        rule("""(?:距(?:离)?(?:活动)?开始|距开抢|距开始还有)\s*:?\s*(\d{1,2}:\d{2}(?::\d{2})?)""") { g, _ -> "Starts in ${g[1]}" }
        rule("""(?:有效期至|有效期到|有效至|有效期截止|截止至?|截止日期)\s*:?\s*(.+)""") { g, l ->
            val rest = g[1].trim()
            val d = if (ChineseText.containsChinese(rest)) translate(rest, l) else rest
            d?.let { "Valid until $it" }
        }

        // ================= Dates & weekdays =================
        rule("""(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号](?:\s*\(?\s*$WD\s*\)?)?(?:\s*$TIME)?""") { g, _ ->
            val mo = g[2].toIntOrNull()?.takeIf { it in 1..12 } ?: return@rule null
            val day = g[3].toIntOrNull()?.takeIf { it in 1..31 } ?: return@rule null
            val sb = StringBuilder()
            if (g[4].isNotEmpty()) sb.append(weekday(g[4], short = true)).append(", ")
            sb.append(MONTHS_SHORT[mo - 1]).append(' ').append(day)
            if (g[1].isNotEmpty()) sb.append(", ").append(g[1])
            if (g[5].isNotEmpty()) sb.append(' ').append(g[5])
            sb.toString()
        }
        rule("""(\d{4})\s*年\s*(\d{1,2})\s*月份?""") { g, _ ->
            g[2].toIntOrNull()?.takeIf { it in 1..12 }?.let { "${MONTHS_SHORT[it - 1]} ${g[1]}" }
        }
        rule("""(\d{4})\s*年""") { g, _ -> g[1] }
        rule("""(\d{1,2})\s*月份?""") { g, _ -> g[1].toIntOrNull()?.takeIf { it in 1..12 }?.let { MONTHS_FULL[it - 1] } }
        rule("""(\d{1,2})\s*[日号]""") { g, _ -> g[1].toIntOrNull()?.takeIf { it in 1..31 }?.let { ordinal(it) } }
        rule("""$WD\s*[至到\-~–—]\s*(?:周|星期|礼拜)?([一二三四五六日天])""") { g, _ ->
            "${weekday(g[1], true)}–${weekday(g[2], true)}"
        }
        rule("""每(?:周|星期)([一二三四五六日天])""") { g, _ -> "Every ${weekday(g[1], false)}" }
        rule("""$WD\s*$TIME(?:\s*[-~至–—]\s*$TIME)?""") { g, _ ->
            "${weekday(g[1], true)} ${g[2]}" + (if (g[3].isNotEmpty()) "–${g[3]}" else "")
        }
        rule(WD) { g, _ -> weekday(g[1], false) }

        // ================= Bare counts with 万 =================
        rule(CNT) { g, _ -> count(g[1], g[2], g[3]) }
    }

    /** Translates [text] if a rule matches the whole (normalized) string, else null. */
    fun translate(text: String, lookup: Lookup = NO_LOOKUP): String? {
        val s = normalize(text)
        if (s.isEmpty() || s.length > 200 || !ChineseText.containsChinese(s)) return null
        for (r in rules) {
            val m = r.regex.matchEntire(s) ?: continue
            val out = r.fn(m.groupValues, lookup)
            if (!out.isNullOrBlank()) return out
        }
        return null
    }

    /**
     * Normalizes fullwidth ASCII variants (！-～, digits, letters, ：，（）) to ASCII, ￥ to ¥,
     * ideographic/no-break spaces to spaces, and collapses whitespace.
     */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(
                when (c) {
                    '　', ' ', '\t', '\n', '\r' -> ' '
                    '￥' -> '¥'
                    in '！'..'～' -> (c.code - 0xFEE0).toChar()
                    else -> c
                },
            )
        }
        return sb.toString().replace(WHITESPACE, " ").trim()
    }

    private val WHITESPACE = Regex("""\s+""")

    // ---- Formatting helpers ----

    /** "1000","","+" -> "1,000+"; "1.2","万","+" -> "12,000+". */
    internal fun count(num: String, unit: String, plus: String): String? {
        val bd = num.replace(",", "").toBigDecimalOrNull() ?: return null
        val mult = when (unit) { "万", "w", "W" -> 10_000; "千", "k", "K" -> 1_000; else -> 1 }
        val v = bd.multiply(BigDecimal(mult)).stripTrailingZeros()
        val s = if (v.scale() <= 0) String.format(Locale.US, "%,d", v.toBigInteger()) else v.toPlainString()
        return s + if (plus.isNotEmpty()) "+" else ""
    }

    /** "20" -> "¥20", "1280.5" -> "¥1,280.5". */
    internal fun price(num: String): String {
        val clean = num.replace(",", "")
        val dot = clean.indexOf('.')
        val intPart = if (dot >= 0) clean.substring(0, dot) else clean
        val frac = if (dot >= 0) clean.substring(dot) else ""
        val grouped = intPart.toBigIntegerOrNull()?.let { String.format(Locale.US, "%,d", it) } ?: intPart
        return "¥$grouped$frac"
    }

    /** "8.5" -> "15", "8" -> "20", "85" -> "15" (two-digit Chinese discount). */
    internal fun discountOff(value: String): String? {
        val v = value.toBigDecimalOrNull() ?: return null
        val off = when {
            v <= BigDecimal.ZERO -> return null
            v < BigDecimal.TEN -> (BigDecimal.TEN - v).multiply(BigDecimal.TEN)
            // Two-digit form: 85折 == 8.5折. "10折" is not a discount.
            v > BigDecimal.TEN && v < BigDecimal(100) && v.stripTrailingZeros().scale() <= 0 -> BigDecimal(100) - v
            else -> return null
        }
        if (off <= BigDecimal.ZERO) return null
        return off.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
    }

    private fun range(a: String, b: String) = if (b.isEmpty()) a else "$a–$b"

    private fun plural(n: String, one: String, many: String): String {
        val formatted = n.toBigIntegerOrNull()?.let { String.format(Locale.US, "%,d", it) } ?: n
        return "$formatted ${if (n == "1") one else many}"
    }

    private fun grams(v: BigDecimal): String {
        val g = v.stripTrailingZeros()
        return if (g >= BigDecimal(1000)) g.divide(BigDecimal(1000)).stripTrailingZeros().toPlainString() + "kg"
        else g.toPlainString() + "g"
    }

    internal fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }

    /** Parses Arabic digits or simple Chinese numerals 1-99 ("三", "十二", "二十五", "两"). */
    internal fun cnInt(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        fun digit(c: Char): Int? = when (c) {
            '一' -> 1; '二', '两' -> 2; '三' -> 3; '四' -> 4; '五' -> 5
            '六' -> 6; '七' -> 7; '八' -> 8; '九' -> 9; else -> null
        }
        if (s.isEmpty()) return null
        val ten = s.indexOf('十')
        if (ten < 0) return if (s.length == 1) digit(s[0]) else null
        val tens = if (ten == 0) 1 else if (ten == 1) digit(s[0]) ?: return null else return null
        val rest = s.substring(ten + 1)
        val ones = when (rest.length) { 0 -> 0; 1 -> digit(rest[0]) ?: return null; else -> return null }
        return tens * 10 + ones
    }

    private fun weekday(c: String, short: Boolean): String {
        val i = when (c) { "一" -> 0; "二" -> 1; "三" -> 2; "四" -> 3; "五" -> 4; "六" -> 5; else -> 6 }
        return if (short) arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[i]
        else arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")[i]
    }

    private fun dayWord(d: String) = when (d) {
        "今天", "今日" -> "Today"; "明天", "明日" -> "Tomorrow"; "后天" -> "Day after tomorrow"
        "昨天", "昨日" -> "Yesterday"; "前天" -> "2 days ago"; else -> ""
    }

    /** "约" -> "~", "<" / 小于 -> "<", a 距... prefix -> " away". */
    private fun distance(near: String, approx: String, less: String, value: String, unit: String): String =
        (if (less.isNotEmpty()) "<" else "") + (if (approx.isNotEmpty() && less.isEmpty()) "~" else "") +
            "$value $unit" + (if (near.isNotEmpty()) " away" else "")

    /** "" -> "", "今天" -> "today ", "明天" -> "tomorrow ". */
    private fun dayLower(d: String) = if (d.isEmpty()) "" else when (d) {
        "后天" -> "the day after tomorrow "
        else -> dayWord(d).lowercase(Locale.US) + " "
    }

    private fun withCategory(base: String, category: String, lookup: Lookup): String? {
        val c = category.trim()
        if (c.isEmpty() || c == "本店" || c == "店内" || c == "全店") return base
        if (c.endsWith("区") || c.endsWith("市")) return null
        val en = lookup.find(c) ?: return null
        return "$base in $en"
    }

    private val HOUR_RANGE = Regex("""(\d{1,2}:\d{2})\s*[-~至到–—]\s*(次日|第二天)?\s*(\d{1,2}:\d{2})""")
    private fun hourRanges(s: String): String = HOUR_RANGE.findAll(s).joinToString(", ") { m ->
        "${m.groupValues[1]}–${m.groupValues[3]}" + if (m.groupValues[2].isNotEmpty()) " (next day)" else ""
    }
}
