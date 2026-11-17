package io.embodit.weatherscape.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class FakeSource(var json: String?, var search: String = """{"results":[]}""") : WeatherSource {
        var calls = 0
        var searchByLang: ((Boolean) -> String)? = null
        var namesOf: ((Long, Boolean) -> String)? = null
        val searchLangs = ArrayList<Boolean>()
        override suspend fun forecast(c: City): String { calls++; return json ?: error("offline") }
        override suspend fun search(q: String, zh: Boolean): String { searchLangs += zh; return searchByLang?.invoke(zh) ?: search }
        override suspend fun names(id: Long, zh: Boolean): String = namesOf?.invoke(id, zh) ?: "{}"
        override suspend fun currents(cs: List<City>): String = "[]"
    }

    private class MemStore : WeatherStore {
        val forecasts = HashMap<String, Pair<String, Long>>()
        var cities: Pair<List<City>, Long?> = emptyList<City>() to null
        override fun loadForecast(key: String) = forecasts[key]
        override fun saveForecast(key: String, json: String, fetchedAtMs: Long) { forecasts[key] = json to fetchedAtMs }
        override fun loadCities() = cities
        override fun saveCities(cities: List<City>, currentId: Long?) { this.cities = cities to currentId }
    }

    /** v0.1.6: no time zone any more; the rig starts with New York saved and chosen (the IP path has its own tests). */
    private class Rig(json: String?, val uiZh: Boolean = true, saved: City? = DefaultCities.ALL.first { it.id == -19L }.copy(lastViewedMs = 1L), wall0: Long = java.time.LocalDateTime.parse("2026-11-12T14:30:00").toInstant(java.time.ZoneOffset.UTC).toEpochMilli()) {
        val disp = StandardTestDispatcher()
        val scope = TestScope(disp)
        var wall = wall0
        var up = 1000L
        var settings = Settings()
        val sounds = ArrayList<Sound>()
        val source = FakeSource(json)
        val store = MemStore().also { st -> if (saved != null) st.cities = listOf(saved) to saved.id }
        val flow = WeatherFlow(scope, disp, { wall }, { up }, source, store, { settings }, { settings = it }, { uiZh }, { false }, { sounds += it }, { println(it) })
        fun frames(ms: Long) { val end = up + ms; while (up < end) { up += 14; flow.step() } }
    }

    private val ny = Fx.forecastJson("2026-11-12T00:00", 96, -18000)

    @Test fun startLiveAndWelcome() {
        val r = Rig(ny)
        r.flow.start(); r.scope.advanceUntilIdle()
        assertEquals("New York", r.flow.value.city!!.nameEn)
        assertEquals(Source.LIVE, r.flow.value.slice.source)
        assertEquals(Phase.LOADING, r.flow.value.phase)
        r.flow.sceneReady()
        assertEquals(Phase.WELCOME, r.flow.value.phase)
        r.flow.welcomeDone()
        assertEquals(Phase.NOW, r.flow.value.phase)
        assertTrue(r.settings.seenHowTo)
    }

    @Test fun dragKeepsReadoutWindowAndStripOnOneHour() {
        val r = Rig(ny); r.settings = Settings(seenHowTo = true)
        r.flow.start(); r.scope.advanceUntilIdle(); r.flow.sceneReady()
        repeat(10) { k ->
            r.flow.ringDragStart(1f)
            for (i in 1..30) { r.up += 14; r.flow.ringDragMove(0.004f * i * (if (k % 3 == 2) -1 else 1)); r.flow.step() }
            r.flow.ringDragEnd()
            r.frames(3000)
            // M2: one state source: shown == the ring's whole hour == what the readout / window / strip read
            assertEquals(r.flow.ring.shown, r.flow.value.shown)
            assertEquals(r.flow.ring.pos, r.flow.ring.shown.toFloat(), 1e-4f)
        }
        assertTrue(r.flow.value.shown > 0)
        assertEquals(Phase.LATER, r.flow.value.phase)
        assertTrue(Sound.TICK in r.sounds)
        r.flow.backToNow(); r.frames(1500)
        assertEquals(0, r.flow.value.shown); assertEquals(Phase.NOW, r.flow.value.phase)
        assertTrue(Sound.CLACK in r.sounds)
    }

    @Test fun offlineStartUsesCacheThenSample() {
        val r = Rig(null)
        r.store.forecasts["c-19"] = ny to (r.wall - 3 * 3_600_000L)
        r.flow.start(); r.scope.advanceUntilIdle()
        assertEquals(Source.CACHE, r.flow.value.slice.source)
        assertEquals(3, r.flow.value.slice.ageHours)
        assertTrue(r.flow.value.offline)
        assertEquals("3 小时前的预报", Texts.sourceNote(r.flow.value.slice, true))
        // M10: the ring still turns
        r.flow.sceneReady(); r.flow.debugScrub(10f)
        assertEquals(10, r.flow.value.shown)
        val none = Rig(null); none.flow.start(); none.scope.advanceUntilIdle()
        assertEquals(Source.SAMPLE, none.flow.value.slice.source)
        assertEquals("示例天气", Texts.sourceNote(none.flow.value.slice, true))
    }

    @Test fun hourRolloverKeepsTheLookedAtTime() {
        val r = Rig(ny); r.settings = Settings(seenHowTo = true)
        r.flow.start(); r.scope.advanceUntilIdle(); r.flow.sceneReady()
        r.flow.debugScrub(10f)
        val target = r.flow.value.slice.at(10).time
        r.wall += 3_600_000L
        r.flow.clockTick()
        assertEquals(9, r.flow.value.shown)
        assertEquals(target, r.flow.value.slice.at(r.flow.value.shown).time)
        // at "now" it keeps following now
        r.flow.backToNow(); r.frames(1500)
        r.wall += 3_600_000L; r.flow.clockTick()
        assertEquals(0, r.flow.value.shown)
        assertEquals(Phase.NOW, r.flow.value.phase)
    }

    @Test fun askTagExpiresAndTheRingClearsIt() {
        val r = Rig(null); r.settings = Settings(seenHowTo = true)
        r.flow.start(); r.scope.advanceUntilIdle(); r.flow.sceneReady()
        r.flow.debugScrub(11f) // sample: overcast, clouds
        r.flow.ask(Ask.SKY)
        assertNotNull(r.flow.value.tag)
        assertEquals("降水 20% · 0 mm", r.flow.tagText(r.flow.value.tag!!, true, TempUnit.C))
        r.frames(3000)
        assertNull(r.flow.value.tag)
        r.flow.ask(Ask.TREE); assertNotNull(r.flow.value.tag)
        r.flow.ringDragStart(1f)
        assertNull(r.flow.value.tag)
        // no clouds -> the sky says nothing
        r.flow.ringDragEnd(); r.flow.debugScrub(0f)
        r.flow.ask(Ask.SKY); assertNull(r.flow.value.tag)
    }

    @Test fun fullListAsksToReplaceTheLeastRecentlyViewed() {
        val r = Rig(ny)
        val a = DefaultCities.ALL[0].copy(lastViewedMs = 30); val b = DefaultCities.ALL[1].copy(lastViewedMs = 10); val c = DefaultCities.ALL[2].copy(lastViewedMs = 20)
        r.store.cities = listOf(a, b, c) to a.id
        r.flow.start(); r.scope.advanceUntilIdle()
        val hz = City(1808926, "杭州市", "", lat = 30.29, lon = 120.16)
        r.flow.pickResult(hz)
        assertEquals(b.id, r.flow.value.search.replace!!.second.id)
        r.flow.confirmReplace(); r.scope.advanceUntilIdle()
        assertEquals(listOf(a.id, c.id, hz.id), r.flow.value.cities.map { it.id })
        assertEquals(hz.id, r.flow.value.city!!.id)
    }

    @Test fun chineseQueryInEnglishUiFindsTheCityAndShowsEnglishNames() {
        // QA #1 (v0.1.0): English UI + "西安" -> only "Xi'ancun". Chinese characters search in Chinese, ranked, then the
        // rows get their English names through /v1/get.
        val r = Rig(ny, uiZh = false)
        r.source.searchByLang = { zh ->
            if (zh) """{"results":[{"id":1984210,"name":"西安村","feature_code":"PPL","admin1":"福建省","country":"中国","latitude":24.9,"longitude":118.2},
                {"id":1790630,"name":"西安","feature_code":"PPLA","population":12952907,"admin1":"陕西","country":"中国","latitude":34.25,"longitude":108.92}]}"""
            else """{"results":[{"id":1984210,"name":"Xi’ancun","feature_code":"PPL","admin1":"Fujian","country":"China","latitude":24.9,"longitude":118.2}]}"""
        }
        r.source.namesOf = { id, _ -> if (id == 1790630L) """{"id":1790630,"name":"Xi'an","admin1":"Shaanxi","country":"China"}""" else """{"id":$id,"name":"Xi’ancun","admin1":"Fujian","country":"China"}""" }
        r.flow.start(); r.scope.advanceUntilIdle()
        r.flow.searchInput("西安"); r.scope.advanceUntilIdle()
        val res = r.flow.value.search.results
        assertEquals(SearchStatus.RESULTS, r.flow.value.search.status)
        assertEquals(1790630L, res.first().id)
        assertEquals("Xi'an", res.first().name(false))
        assertEquals("Shaanxi · China", res.first().place(false))
        assertEquals(listOf(true), r.source.searchLangs)
    }

    @Test fun stripTapReachesEveryHourWithoutDragging() {
        // D53 (kept Shared Space): the tap path covers everything the ring does: any hour 0..end, and back to now
        val r = Rig(ny); r.settings = Settings(seenHowTo = true)
        r.flow.start(); r.scope.advanceUntilIdle(); r.flow.sceneReady()
        val end = r.flow.ring.end
        assertTrue("end $end", end >= 40)
        for (h in listOf(1, 7, 15, 23, 31, end, 3, 0)) {
            r.flow.gotoHour(h); r.frames(2_000)
            assertEquals("hour $h", h, r.flow.value.shown)
            assertEquals(if (h == 0) Phase.NOW else Phase.LATER, r.flow.value.phase)
        }
        // ask works after a tap jump too (tree / house always answer)
        r.flow.gotoHour(15); r.frames(2_000)
        r.flow.ask(Ask.HOUSE); assertEquals(15, r.flow.value.tag!!.hour)
    }

    @Test fun stormFlashesThenOneThunder() {
        val r = Rig(null); r.settings = Settings(seenHowTo = true)
        r.flow.start(); r.scope.advanceUntilIdle(); r.flow.sceneReady()
        r.flow.debugScrub(19f)
        var flashes = 0; var wasOn = false
        repeat(400) { r.up += 14; r.flow.step(); val on = r.flow.flashOn(r.up); if (on && !wasOn) flashes++; wasOn = on }
        assertEquals(2, flashes)
        assertEquals(1, r.sounds.count { it == Sound.THUNDER })
    }

    @Test fun muteAndLevelsAreOneValue() {
        var s = Settings(sound = 2)
        s = s.toggleMute(); assertTrue(s.muted); assertEquals(0, s.sound)
        s = s.toggleMute(); assertEquals(2, s.sound)
        s = s.withSound(3).withSound(0).toggleMute(); assertEquals(3, s.sound)
        assertEquals(listOf(0, 35, 65, 100), Settings.PERCENT.toList())
    }
}
