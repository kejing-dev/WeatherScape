package io.embodit.weatherscape.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

/** M14 (the city's local "now", not the device's) and M16 (current folded into hour 0); parsing of search / currents. */
class OpenMeteoTest {
    // New York, EST (-5 h): forecast starts at local midnight 2026-11-12, 96 hours
    private val f = OpenMeteo.parseForecast(Fx.forecastJson("2026-11-12T00:00", 96, -18000, currentCode = 3, currentTemp = 21.5))

    private fun utc(iso: String) = LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun nowIsTheCitysLocalHour() {
        // device in Shanghai at 2026-11-12 22:30 (+8) = 14:30 UTC = 09:30 in New York -> hour 0 is 09:00 local
        val s = OpenMeteo.slice(f, utc("2026-11-12T14:30:00"), Source.LIVE)!!
        assertEquals("2026-11-12T09:00", s.hours[0].time)
        assertEquals(9, s.hours[0].hod)
        assertEquals(48, s.hours.size)
        assertEquals(0, s.hours[0].dayIndex)
        assertEquals(1, s.hours[15].dayIndex) // 09 + 15 = 24 -> tomorrow
        assertEquals(47, s.endHour)
    }

    @Test fun currentFoldsIntoHourZero() {
        val s = OpenMeteo.slice(f, utc("2026-11-12T05:10:00"), Source.LIVE)!! // 00:10 local -> 00:00, the current hour
        assertEquals(21.5f, s.hours[0].temp, 1e-4f)
        assertEquals(3, s.hours[0].code)
        // M16: readout, scene and window all read hours[0]
        assertEquals(Sky.OVERCAST, SceneModel.at(s, 0f).sky)
        assertEquals("阴 · 22°", Texts.readout(s, 0, true, TempUnit.C).second)
    }

    @Test fun nullProbabilityStaysNull() {
        val s = OpenMeteo.slice(f, utc("2026-11-12T05:00:00"), Source.LIVE)!!
        assertNull(s.hours[5].pop)
    }

    @Test fun shortCacheEndsTheRingEarlyAndExpiredGivesNull() {
        val late = OpenMeteo.slice(f, utc("2026-11-16T00:30:00"), Source.CACHE, 60)!! // 19:30 local on the 4th day -> 5 hours left
        assertEquals(5, late.hours.size)
        assertEquals(4, late.endHour)
        assertNull(OpenMeteo.slice(f, utc("2026-11-17T00:00:00"), Source.CACHE))
    }

    @Test fun sunTimesPerDay() {
        val s = OpenMeteo.slice(f, utc("2026-11-12T14:30:00"), Source.LIVE)!!
        assertEquals(6 * 60 + 40, s.day(0)!!.riseMin)
        assertEquals(16 * 60 + 50, s.day(1)!!.setMin)
    }

    @Test fun searchAndNames() {
        val zh = """{"results":[{"id":1808926,"name":"杭州市","latitude":30.29365,"longitude":120.16142,"timezone":"Asia/Shanghai","country":"中国","admin1":"浙江省"}]}"""
        val c = OpenMeteo.parseSearch(zh, true).single()
        assertEquals("杭州市", c.name(true)); assertEquals("浙江省 · 中国", c.place(true))
        assertEquals("杭州市", c.name(false)) // English falls back until /v1/get fills it
        val en = """{"id":1808926,"name":"Hangzhou","latitude":30.29365,"longitude":120.16142,"timezone":"Asia/Shanghai","country":"China","admin1":"Zhejiang"}"""
        val m = OpenMeteo.mergeNames(c, en, false)
        assertEquals("Hangzhou", m.name(false)); assertEquals("Zhejiang · China", m.place(false))
        assertTrue(OpenMeteo.parseSearch("""{"generationtime_ms":0.3}""", true).isEmpty())
    }

    @Test fun searchRanksTheBigCityFirst() {
        // QA #1: English UI, "西安" -> Xi'ancun (a village in Fujian) was first. Chinese characters search in Chinese and
        // the hits are ranked: exact name, then capital / seat, then population.
        assertTrue(OpenMeteo.searchZh("西安", uiZh = false))
        assertTrue(!OpenMeteo.searchZh("Xi'an", uiZh = false))
        assertTrue(OpenMeteo.searchZh("Xi'an", uiZh = true))
        val zh = """{"results":[
            {"id":1984210,"name":"西安村","feature_code":"PPL","latitude":24.9,"longitude":118.2,"admin1":"福建省","country":"中国"},
            {"id":2005499,"name":"西安","feature_code":"PPL","latitude":25.1,"longitude":117.4,"admin1":"福建省","country":"中国"},
            {"id":1790631,"name":"西安","feature_code":"PPLA4","latitude":27.0,"longitude":111.0,"admin1":"湖南","country":"中国"},
            {"id":1790630,"name":"西安","feature_code":"PPLA","population":12952907,"latitude":34.25833,"longitude":108.92861,"admin1":"陕西","country":"中国"}]}"""
        val r = OpenMeteo.parseSearch(zh, true, "西安")
        assertEquals(listOf(1790630L, 1790631L, 2005499L), r.map { it.id })
        val en = """{"results":[
            {"id":1,"name":"Springfield","feature_code":"PPL","population":1000},
            {"id":2,"name":"Springfield","feature_code":"PPLA","population":116000},
            {"id":3,"name":"Springfield Airport","feature_code":"AIRP"},
            {"id":4,"name":"Springfield","feature_code":"PPLA2","population":169000}]}"""
        assertEquals(listOf(2L, 4L, 1L), OpenMeteo.parseSearch(en, false, "springfield").map { it.id })
    }

    @Test fun currentsOneOrMany() {
        assertEquals(listOf(12.5f to 3), OpenMeteo.parseCurrents("""{"current":{"temperature_2m":12.5,"weather_code":3}}"""))
        assertEquals(2, OpenMeteo.parseCurrents("""[{"current":{"temperature_2m":1,"weather_code":0}},{"current":{"temperature_2m":2,"weather_code":61}}]""").size)
    }

    @Test fun defaultCityByZone() {
        // v0.1.6 (D56): the table only suggests cities; nothing picks one from the device time zone any more
        assertEquals(24, DefaultCities.ALL.size)
        assertTrue(DefaultCities.ALL.all { it.id in -24L..-1L })
    }

    @Test fun timeWords() {
        val s = Sample.slice()
        assertEquals("现在", Texts.time(s, 0, true))
        assertEquals("今天早上 8 点", Texts.time(s, 8, true))
        assertEquals("今晚 9 点", Texts.time(s, 21, true))
        assertEquals("明早 8 点", Texts.time(s, 32, true))
        assertEquals("Tomorrow 8 AM", Texts.time(s, 32, false))
        assertEquals("Tonight 9 PM", Texts.time(s, 21, false))
        // English readout strings fit the 27.5 cm text area at ~1.05 cm per character (design spatialLayout.readout)
        for (h in 0..47) { val (a, b) = Texts.readout(s, h, false, TempUnit.F); assertTrue("$a / $b", a.length <= 26 && b.length <= 26) }
    }

    @Test fun windWords() {
        assertEquals("东北", Wind.from(45, true)); assertEquals("NE", Wind.from(45, false))
        assertEquals(3, Wind.beaufort(14f))
        assertEquals("东北风 3 级 · 14 km/h", Texts.tagTree(Fx.hour(0, wind = 14f).copy(windDir = 45), true, TempUnit.C))
        assertEquals("NE wind · 9 mph", Texts.tagTree(Fx.hour(0, wind = 14f).copy(windDir = 45), false, TempUnit.F))
    }
}
