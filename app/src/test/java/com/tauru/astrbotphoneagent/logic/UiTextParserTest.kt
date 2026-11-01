package com.tauru.astrbotphoneagent.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTextParserTest {
    @Test fun extractsNodeTextsInDocumentOrderAndDropsBlanks() {
        val xml = """<node index="0" text="设置" content-desc="返回"/><node text=""/><node text="   "/><node text="消息 99+"/>"""
        assertEquals(listOf("设置", "消息 99+"), UiTextParser.parse(xml))
    }

    @Test fun parsesDumpsysViewHierarchyTextFields() {
        val dump = "android.widget.TextView{5284c9a V.ED.... ......ID 0,107-1080,222}\n" +
            "      text=\"今天 14:30\"\n" +
            "android.widget.ImageView{7fceba8 V.ED..... ......I. 0,0-0,0}\n" +
            "      text=\"\""
        assertEquals(listOf("今天 14:30"), UiTextParser.parse(dump))
    }

    @Test fun skipsTextsLongerThanTheCap() {
        val tooLong = "x".repeat(121)
        val xml = """<node text="$tooLong"/><node text="短"/>"""
        assertEquals(listOf("短"), UiTextParser.parse(xml))
    }

    @Test fun capsTheNumberOfNodes() {
        val xml = (1..100).joinToString("") { """<node text="条目$it"/>""" }
        val parsed = UiTextParser.parse(xml)
        assertEquals(80, parsed.size)
        assertEquals("条目80", parsed.last())
    }

    @Test fun emptyOrTextlessInputYieldsNothing() {
        assertTrue(UiTextParser.parse("").isEmpty())
        assertTrue(UiTextParser.parse("""<node text="" content-desc="仅描述"/>""").isEmpty())
    }
}
