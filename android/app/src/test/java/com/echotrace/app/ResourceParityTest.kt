package com.echotrace.app

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ResourceParityTest {
    private fun res(rel: String): String {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "src/main/res/$rel"); if (f.exists()) return f.readText()
            val g = File(d, "app/src/main/res/$rel"); if (g.exists()) return g.readText()
            d = d.parentFile
        }
        fail("missing $rel"); return ""
    }
    private fun strings(x: String) = Regex("<string name=\"(\\w+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        .findAll(x).associate { it.groupValues[1] to it.groupValues[2] }
    private fun colors(x: String) = Regex("<color name=\"(\\w+)\">(#[0-9A-Fa-f]+)</color>").findAll(x).associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun everyHebrewStringHasEnglishWithSamePlaceholders() {
        val he = strings(res("values/strings.xml")); val en = strings(res("values-en/strings.xml"))
        assertTrue(he.size > 40)
        assertEquals("missing in en", emptySet<String>(), he.keys - en.keys)
        val ph = Regex("%\\d\\$[sd]")
        for ((k, v) in he) assertEquals("placeholders differ for $k", ph.findAll(v).map { it.value }.toList(), ph.findAll(en[k]!!).map { it.value }.toList())
    }
    @Test fun noEnglishStringContainsHebrew() {
        for ((k, v) in strings(res("values-en/strings.xml"))) assertFalse("hebrew in en/$k", v.any { it in '\u0590'..'\u05FF' })
    }
    @Test fun lightAndDarkPalettesDefineTheSameColors() {
        val l = colors(res("values/colors_main.xml")); val d = colors(res("values-night/colors_main.xml"))
        assertEquals(l.keys, d.keys); assertTrue(l.size >= 7)
        assertNotEquals(l["m_bg"], d["m_bg"])
    }
    @Test fun layoutOnlyUsesDefinedStringsAndColors() {
        val layout = res("layout/activity_main.xml")
        val defined = strings(res("values/strings.xml")).keys
        for (m in Regex("@string/(\\w+)").findAll(layout)) assertTrue("undefined string ${m.groupValues[1]}", m.groupValues[1] in defined)
        val colors = colors(res("values/colors_main.xml")).keys
        for (m in Regex("@color/(m_\\w+)").findAll(layout)) assertTrue("undefined color ${m.groupValues[1]}", m.groupValues[1] in colors)
        assertFalse("hardcoded rtl in main layout", layout.contains("layoutDirection=\"rtl\""))
    }
    @Test fun contrastIsReadableInBothThemes() {
        fun lum(h: String): Double { val c = (1..5 step 2).map { Integer.parseInt(h.substring(it, it + 2), 16) / 255.0 }.map { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }; return 0.2126*c[0]+0.7152*c[1]+0.0722*c[2] }
        fun ratio(a: String, b: String): Double { val x = lum(a); val y = lum(b); return (maxOf(x,y)+0.05)/(minOf(x,y)+0.05) }
        for (f in listOf("values/colors_main.xml", "values-night/colors_main.xml")) {
            val c = colors(res(f))
            assertTrue("ink on bg $f", ratio(c["m_ink"]!!, c["m_bg"]!!) >= 7.0)
            assertTrue("secondary on bg $f", ratio(c["m_sec"]!!, c["m_bg"]!!) >= 4.5)
            assertTrue("button text on button $f", ratio(c["m_btn_text"]!!, c["m_btn"]!!) >= 3.0)
        }
    }
}
