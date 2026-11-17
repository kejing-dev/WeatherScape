package io.embodit.weatherscape.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

/**
 * v0.1.6 (profile D56, user 2026-11-16: 「我在 san jose 他怎么显示就4 摄氏度，headset 系统时间也不对，别用系统时间，用你call
 * 的气象站的」; the headset said America/New_York). The city comes from IP geolocation, the time from the weather server:
 * with the device zone set to New York or Shanghai, and the device clock right or 3 h off, the same San Jose answer shows
 * the same city, local time, current hour, sunrise / sunset and observation time. IP failing -> the user picks a city.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimePlaceTest {
    private val savedZone = TimeZone.getDefault()
    @After fun restoreZone() = TimeZone.setDefault(savedZone)

    /** True UTC of the test: 2026-11-17 07:20 Z = 2026-11-16 23:20 in San Jose (PST, -8 h). */
    private val trueUtc = Instant.parse("2026-11-17T07:20:00Z").toEpochMilli()
    private val sjOffset = -28800

    private fun sjForecast(): String {
        val t0 = java.time.LocalDateTime.parse("2026-11-16T00:00")
        val n = 96
        val times = (0 until n).map { "\"" + t0.plusHours(it.toLong()).toString().take(16) + "\"" }
        fun arr(v: (Int) -> String) = (0 until n).joinToString(",", "[", "]") { v(it) }
        val dates = (0 until 4).map { "\"" + t0.toLocalDate().plusDays(it.toLong()) + "\"" }
        return """{"latitude":37.34,"longitude":-121.89,"utc_offset_seconds":$sjOffset,"timezone":"America/Los_Angeles",
            "current":{"time":"2026-11-16T23:15","temperature_2m":13.4,"apparent_temperature":12.1,"weather_code":2,"is_day":0},
            "hourly":{"time":[${times.joinToString(",")}],"temperature_2m":${arr { "${10 + it % 7}" }},"apparent_temperature":${arr { "${9 + it % 7}" }},
            "precipitation_probability":${arr { "0" }},"precipitation":${arr { "0.0" }},"snowfall":${arr { "0.0" }},
            "weather_code":${arr { "2" }},"cloud_cover":${arr { "40" }},"wind_speed_10m":${arr { "6.0" }},"wind_direction_10m":${arr { "300" }},"is_day":${arr { if (it % 24 in 7..16) "1" else "0" }}},
            "daily":{"time":[${dates.joinToString(",")}],"sunrise":[${dates.joinToString(",") { it.dropLast(1) + "T06:58\"" }}],"sunset":[${dates.joinToString(",") { it.dropLast(1) + "T16:56\"" }}],
            "temperature_2m_max":[17,18,16,15],"temperature_2m_min":[8,9,7,6]}}"""
    }

    private val sjSearch = """{"results":[
        {"id":3621849,"name":"San José","latitude":9.93333,"longitude":-84.08333,"feature_code":"PPLC","country":"Costa Rica","admin1":"San José","timezone":"America/Costa_Rica","population":335007},
        {"id":5392171,"name":"San Jose","latitude":37.33939,"longitude":-121.89496,"feature_code":"PPLA2","country":"United States","admin1":"California","timezone":"America/Los_Angeles","population":1026908}]}"""
    private val sjZh = """{"id":5392171,"name":"圣何塞","latitude":37.33939,"longitude":-121.89496,"country":"美国","admin1":"加利福尼亚州"}"""
    private val ipwhoSj = """{"ip":"203.0.113.9","success":true,"city":"San Jose","region":"California","country":"United States","latitude":37.3382,"longitude":-121.8863,"timezone":{"id":"America/Los_Angeles"}}"""

    private class Src(val forecast: String, val dateMs: Long?, val ip: Map<GeoIp.Provider, String>, val search: String, val zhNames: String) : WeatherSource {
        val asked = ArrayList<City>()
        val ipCalls = ArrayList<GeoIp.Provider>()
        override suspend fun forecast(c: City): String { asked += c; return forecast }
        override suspend fun forecastReply(c: City): Reply { asked += c; return Reply(forecast, dateMs) }
        override suspend fun ipLocate(p: GeoIp.Provider): String { ipCalls += p; return ip[p] ?: error("timeout") }
        override suspend fun search(q: String, zh: Boolean): String = search
        override suspend fun names(id: Long, zh: Boolean): String = if (zh) zhNames else "{}"
        override suspend fun currents(cs: List<City>): String = "[]"
    }

    private class Mem(var cities: Pair<List<City>, Long?> = emptyList<City>() to null) : WeatherStore {
        override fun loadForecast(key: String): Pair<String, Long>? = null
        override fun saveForecast(key: String, json: String, fetchedAtMs: Long) {}
        override fun loadCities() = cities
        override fun saveCities(cities: List<City>, currentId: Long?) { this.cities = cities to currentId }
    }

    private class Run(val flow: WeatherFlow, val src: Src, val store: Mem, val scope: TestScope)

    private fun run(zone: String, deviceMs: Long, dateMs: Long?, ip: Map<GeoIp.Provider, String>, saved: List<City> = emptyList()): Run {
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        val disp = StandardTestDispatcher(); val scope = TestScope(disp)
        val src = Src(sjForecast(), dateMs, ip, sjSearch, sjZh)
        val store = Mem(saved to saved.firstOrNull()?.id)
        var settings = Settings(seenHowTo = true)
        val flow = WeatherFlow(scope, disp, { deviceMs }, { 1000L }, src, store, { settings }, { settings = it }, { true }, { false }, {}, { println(it) })
        flow.start(); scope.advanceUntilIdle(); flow.sceneReady()
        return Run(flow, src, store, scope)
    }

    private val ipOk = mapOf(GeoIp.Provider.IPAPI to """{"error":true,"reason":"RateLimited"}""", GeoIp.Provider.IPWHO to ipwhoSj)

    /** What the panel and the scene read for "now": city, observed time, local clock, current hour, sun times, readout. */
    private fun shown(r: Run): List<Any?> {
        val st = r.flow.value
        val s = st.slice
        return listOf(st.city?.nameEn, st.city?.nameZh, st.observed, ServerClock.localHm(r.flow.now(), sjOffset), s.hours.first().time, s.hours.first().hod,
            s.hours.first().temp, s.days.map { it.riseMin to it.setMin }, Texts.time(s, 0, true), Texts.time(s, 5, true), s.source)
    }

    @Test fun sameSanJoseAnswerShowsTheSameTimeInAnyDeviceZone() {
        val ny = run("America/New_York", trueUtc, trueUtc, ipOk)
        val sh = run("Asia/Shanghai", trueUtc, trueUtc, ipOk)
        val a = shown(ny); val b = shown(sh)
        println("NY device zone: $a\nShanghai device zone: $b")
        assertEquals(a, b)
        assertEquals("San Jose", ny.flow.value.city!!.nameEn)
        assertEquals("圣何塞", ny.flow.value.city!!.nameZh)
        assertEquals("23:15", ny.flow.value.observed)
        assertEquals("23:20", ServerClock.localHm(ny.flow.now(), sjOffset))
        assertEquals("2026-11-16T23:00", ny.flow.value.slice.hours.first().time)
        assertEquals(13.4f, ny.flow.value.slice.hours.first().temp, 0.01f) // current folded into hour 0, not New York's 4 °C
        // the forecast was asked for San Jose's coordinates (the geocoded city), not a time-zone city
        val asked = ny.src.asked.last()
        assertTrue("asked $asked", GeoIp.km(asked.lat, asked.lon, 37.3382, -121.8863) < 10.0)
        assertEquals(listOf(GeoIp.Provider.IPAPI, GeoIp.Provider.IPWHO), ny.src.ipCalls) // first failed, the next one answered
    }

    @Test fun aWrongDeviceClockIsCorrectedByTheServerDate() {
        val right = run("America/New_York", trueUtc, trueUtc, ipOk)
        val ahead = run("Asia/Shanghai", trueUtc + 3 * 3_600_000L, trueUtc, ipOk) // device 3 h fast
        val behind = run("America/New_York", trueUtc - 3 * 3_600_000L, trueUtc, ipOk) // device 3 h slow
        assertEquals(shown(right), shown(ahead))
        assertEquals(shown(right), shown(behind))
        assertEquals(-3 * 3_600_000L, ahead.flow.clockSkewMs)
        // no Date header: current.time - utc_offset (within its 15-minute step) still wins over a 3 h wrong clock
        val noDate = run("Asia/Shanghai", trueUtc + 3 * 3_600_000L, null, ipOk)
        assertEquals("2026-11-16T23:00", noDate.flow.value.slice.hours.first().time)
        assertTrue(ServerClock.localHm(noDate.flow.now(), sjOffset) in listOf("23:15", "23:20", "23:22", "23:25"))
    }

    @Test fun ipFailsEverywhereSoTheUserPicksACity() {
        for (zone in listOf("America/New_York", "Asia/Shanghai")) {
            val r = run(zone, trueUtc, trueUtc, emptyMap())
            val st = r.flow.value
            assertEquals(GeoIp.PROVIDERS, r.src.ipCalls)
            assertNull("no city guessed from the zone $zone", st.city)
            assertTrue(st.noCity)
            assertEquals(Locate.FAILED, st.locate)
            assertEquals(Source.SAMPLE, st.slice.source)
            assertTrue(r.src.asked.isEmpty())
            // picking a city works as before
            r.flow.pickResult(DefaultCities.ALL.first { it.nameEn == "San Francisco" }); r.scope.advanceUntilIdle()
            assertEquals("San Francisco", r.flow.value.city!!.nameEn)
            assertFalse(r.flow.value.noCity)
            assertEquals(Locate.IDLE, r.flow.value.locate)
        }
    }

    @Test fun theV015TimeZoneGuessIsReplacedAndKept() {
        val guess = DefaultCities.ALL.first { it.nameEn == "New York" } // saved by v0.1.5, never viewed
        val r = run("America/New_York", trueUtc, trueUtc, ipOk, saved = listOf(guess))
        val st = r.flow.value
        assertEquals("San Jose", st.city!!.nameEn)
        assertEquals(listOf("New York", "San Jose"), st.cities.map { it.nameEn }) // the saved list stays
        // a city the user chose (viewed) is left alone: no IP lookup at start
        val chosen = run("America/New_York", trueUtc, trueUtc, ipOk, saved = listOf(guess.copy(lastViewedMs = 5L)))
        assertEquals("New York", chosen.flow.value.city!!.nameEn)
        assertTrue(chosen.src.ipCalls.isEmpty())
    }

    @Test fun useMyLocationFromSettings() {
        val tokyo = DefaultCities.ALL.first { it.nameEn == "Tokyo" }.copy(lastViewedMs = 5L)
        val r = run("Asia/Shanghai", trueUtc, trueUtc, ipOk, saved = listOf(tokyo))
        assertEquals("Tokyo", r.flow.value.city!!.nameEn)
        r.flow.locate("settings"); r.scope.advanceUntilIdle()
        assertEquals("San Jose", r.flow.value.city!!.nameEn)
        assertEquals(2, r.flow.value.cities.size)
        // the list is full -> the replace question (cities page), nothing dropped silently
        val full = listOf("Tokyo", "Paris", "London").map { n -> DefaultCities.ALL.first { it.nameEn == n }.copy(lastViewedMs = 5L) }
        val f = run("Asia/Shanghai", trueUtc, trueUtc, ipOk, saved = full)
        f.flow.locate("settings"); f.scope.advanceUntilIdle()
        assertNotNull(f.flow.value.search.replace)
        assertEquals("San Jose", f.flow.value.search.replace!!.first.nameEn)
        // already saved (within 10 km) -> just selected
        val again = run("Asia/Shanghai", trueUtc, trueUtc, ipOk)
        again.flow.locate("settings"); again.scope.advanceUntilIdle()
        assertEquals(1, again.flow.value.cities.size)
    }

    @Test fun ipAnswersParse() {
        val a = GeoIp.parse(GeoIp.Provider.IPAPI, """{"ip":"x","city":"San Jose","region":"California","country_name":"United States","latitude":37.3394,"longitude":-121.895,"timezone":"America/Los_Angeles"}""")!!
        assertEquals("San Jose", a.city); assertEquals(37.3394, a.lat, 1e-6)
        assertNull(GeoIp.parse(GeoIp.Provider.IPAPI, """{"error":true,"reason":"RateLimited"}"""))
        assertEquals("America/Los_Angeles", GeoIp.parse(GeoIp.Provider.IPWHO, ipwhoSj)!!.tz)
        assertNull(GeoIp.parse(GeoIp.Provider.IPWHO, """{"success":false,"message":"Reserved range"}"""))
        assertEquals(-121.8863, GeoIp.parse(GeoIp.Provider.IPINFO, """{"city":"San Jose","region":"California","country":"US","loc":"37.3382,-121.8863","timezone":"America/Los_Angeles"}""")!!.lon, 1e-6)
        assertNull(GeoIp.parse(GeoIp.Provider.IPINFO, "<html>"))
        // the nearest geocoding hit, not the capital of Costa Rica
        val c = GeoIp.nearest(sjSearch, GeoIp.parse(GeoIp.Provider.IPWHO, ipwhoSj)!!)!!
        assertEquals(5392171L, c.id)
        // no Date header: current.time - offset
        assertEquals(Instant.parse("2026-11-17T07:15:00Z").toEpochMilli(), ServerClock.currentUtcMs("2026-11-16T23:15", sjOffset))
        assertEquals("23:15", ServerClock.observedHm("2026-11-16T23:15"))
    }
}
