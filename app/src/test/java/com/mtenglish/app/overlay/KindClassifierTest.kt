package com.mtenglish.app.overlay

import com.mtenglish.app.translate.TextKind
import org.junit.Assert.assertEquals
import org.junit.Test

class KindClassifierTest {
    private fun kind(text: String, cls: String? = "android.widget.TextView", id: String? = null, clickable: Boolean = false, hint: Boolean = false) =
        KindClassifier.classify(text, cls, id, clickable, hint)

    @Test fun hintWins() = assertEquals(TextKind.FORM_HINT, kind("请输入手机号", id = "x:id/poi_name", hint = true))

    @Test fun idTokens() {
        assertEquals(listOf("txt", "poi", "name"), KindClassifier.idTokens("com.sankuai.meituan:id/txtPoiName"))
        assertEquals(listOf("tv", "food", "desc"), KindClassifier.idTokens("tv_food_desc"))
        assertEquals(emptyList<String>(), KindClassifier.idTokens(null))
    }

    @Test fun idBasedKinds() {
        assertEquals(TextKind.STORE_NAME, kind("老王烧烤", id = "com.sankuai.meituan.takeoutnew:id/txt_poi_name"))
        assertEquals(TextKind.MENU_ITEM, kind("宫保鸡丁", id = "pkg:id/food_name"))
        assertEquals(TextKind.MENU_ITEM, kind("宫保鸡丁", id = "pkg:id/spuTitle"))
        assertEquals(TextKind.REVIEW, kind("很好吃", id = "pkg:id/comment_content"))
        assertEquals(TextKind.DESCRIPTION, kind("本店招牌", id = "pkg:id/poi_desc"))
        assertEquals(TextKind.UI_LABEL, kind("起送¥20", id = "pkg:id/shop_price"))
        assertEquals(TextKind.ADDRESS, kind("朝阳区建国路", id = "pkg:id/tv_address"))
    }

    @Test fun heuristicKinds() {
        assertEquals(TextKind.UI_LABEL, kind("去结算", cls = "android.widget.Button"))
        assertEquals(TextKind.UI_LABEL, kind("全部订单", clickable = true))
        assertEquals(TextKind.DESCRIPTION, kind("这是一段很长很长的商品描述文字，用来介绍菜品的口味、分量和做法等等信息"))
        assertEquals(TextKind.GENERIC, kind("今日特价推荐"))
    }
}
