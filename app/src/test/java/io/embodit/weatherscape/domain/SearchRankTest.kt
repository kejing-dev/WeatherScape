package io.embodit.weatherscape.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.1.3 (QA #3 q3-1 = q2-1, K1): real Open-Meteo answers saved 2026-11-13 (test/resources/geo). Chinese and English UI
 * x Paris / London / 东京 / 北京: the first row is the big city. Requests are built by the same [OpenMeteo.searchQueries]
 * the flow uses; a query with no saved answer reads as no results.
 */
class SearchRankTest {
    private val file = mapOf("东京" to "dongjing_s", "東京" to "dongjing_t", "伦敦" to "lundun_s", "倫敦" to "lundun_t", "北京" to "beijing_cjk")

    private fun answer(q: String, zh: Boolean): String {
        val name = (file[q] ?: q) + (if (zh) "-zh" else "-en") + ".json"
        return javaClass.classLoader!!.getResourceAsStream("geo/$name")?.bufferedReader()?.readText() ?: """{"results":[]}"""
    }

    private fun search(q: String, uiZh: Boolean): List<City> {
        val sz = OpenMeteo.searchZh(q, uiZh)
        val texts = OpenMeteo.searchQueries(q, uiZh).map { answer(it, sz) }
        return OpenMeteo.parseSearchMany(texts, sz, q)
    }

    private fun first(q: String, uiZh: Boolean) = search(q, uiZh).first()

    @Test fun parisInBothUis() {
        val zh = first("Paris", true); assertEquals("巴黎", zh.nameZh); assertEquals("法国", zh.countryZh)
        val en = first("Paris", false); assertEquals("Paris", en.nameEn); assertEquals("France", en.countryEn)
    }

    @Test fun londonInBothUis() {
        val zh = first("London", true); assertEquals("伦敦", zh.nameZh); assertEquals("英国", zh.countryZh)   // GeoNames 倫敦 shown simplified
        val en = first("London", false); assertEquals("London", en.nameEn); assertEquals("United Kingdom", en.countryEn)
        // Chinese query: 伦敦 (simplified) alone finds London, Ontario first; the 倫敦 / London requests bring the UK one
        val cjk = first("伦敦", false); assertEquals("英国", cjk.countryZh)
        assertEquals("英国", first("伦敦", true).countryZh)
    }

    @Test fun tokyoInBothUis() {
        // K1: 东京 (simplified) only finds two villages in China; 東京 / Tokyo find the capital
        assertTrue(OpenMeteo.parseSearch(answer("东京", true), true, "东京").none { it.countryZh == "日本" })
        val zh = first("东京", true); assertEquals("东京", zh.nameZh); assertEquals("日本", zh.countryZh)
        assertEquals("日本", first("东京", false).countryZh)
        assertEquals("Japan", first("Tokyo", false).countryEn)
        assertEquals("日本", first("Tokyo", true).countryZh)
    }

    @Test fun beijingInBothUis() {
        assertEquals(39.9, first("北京", true).lat, 0.2)
        assertEquals(39.9, first("北京", false).lat, 0.2)
        assertEquals("北京", first("Beijing", true).nameZh)
        assertEquals("Beijing", first("Beijing", false).nameEn)
    }

    @Test fun traditionalSpellingsAndAliases() {
        assertEquals("東京", Hanzi.toTraditional("东京")); assertEquals("伦敦", Hanzi.toSimplified("倫敦"))
        assertEquals(listOf("东京", "東京", "Tokyo"), OpenMeteo.searchQueries("东京", true))
        assertEquals(listOf("Paris"), OpenMeteo.searchQueries("Paris", true))
        assertTrue("New York" in OpenMeteo.aliasesOf("纽约市"))
    }
}
