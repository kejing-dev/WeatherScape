package io.embodit.weatherscape.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.1.2: 8 time blocks + ◀ ▶ (M17 / M18), the hold repeat, the white clouds / smoke (B24). */
@OptIn(ExperimentalCoroutinesApi::class)
class ControlsTest {

    // ---------------------------------------------------------------- M17: every hour <= 4 presses

    @Test fun everyHourWithinFourPressesFromAnyHour() {
        for (end in listOf(47, 40, 23)) {
            var worst = 0
            for (from in 0..end) for (to in 0..end) {
                val n = StripMath.presses(from, to, end)
                assertTrue("end=$end $from -> $to took $n", n <= 4)
                worst = maxOf(worst, n)
            }
            println("M17 end=$end worst=$worst presses")
        }
    }

    @Test fun blocksAreSixHoursAndTapTheMiddle() {
        assertEquals(8, StripMath.BLOCKS)
        assertEquals((0 until 8).map { it * 6 + 3 }, (0 until 8).map { StripMath.target(it, 47) })
        assertEquals(0, StripMath.blockOf(5)); assertEquals(1, StripMath.blockOf(6)); assertEquals(7, StripMath.blockOf(47))
        // end 40: block 7 (42..47) is greyed, block 6 jumps to min(39, 40)
        assertFalse(StripMath.enabled(7, 40)); assertTrue(StripMath.enabled(6, 40))
        assertEquals(39, StripMath.target(6, 40))
        assertEquals(37, StripMath.target(6, 37))
    }

    /** M17 sizes: blocks >= 64 x 64 dp, 1 dp ≈ 0.039° at the window distance -> each >= 2°. */
    @Test fun blockAndButtonSizesAtLeastTwoDegrees() {
        val windowW = 760f; val pad = 24f
        val block = (windowW - 2 * pad) / StripMath.BLOCKS
        assertTrue("block $block dp", block >= 64f)
        val stripH = 156f
        assertTrue(stripH >= 64f)
        for ((name, dp) in listOf("block" to block, "block h" to stripH, "step" to 72f, "back" to 72f)) assertTrue("$name ${dp * 0.039f}°", dp * 0.039f >= 2f)
    }

    @Test fun axisTicksCarryDayNamesAndClockHours() {
        // the slice starts at 21:00 today
        val hods = (0 until 48).map { (21 + it) % 24 }
        val days = (0 until 48).map { (21 + it) / 24 }
        val t = StripMath.ticks(hods, days, 47, { d -> listOf("今天", "明天", "后天")[d] }, { h -> "$h 时" })
        assertEquals(StripMath.Tick(0, "今天", true), t.first())
        assertTrue(t.any { it.hour == 3 && it.text == "明天" && it.day })
        assertTrue(t.any { it.hour == 27 && it.text == "后天" && it.day })
        assertTrue(t.any { it.hour == 9 && it.text == "6 时" })
        assertTrue(t.none { !it.day && hods[it.hour] % 6 != 0 })
        // placement on a 712 dp strip: day names never dropped, no two labels overlap
        val w = 712f; val cw = w / 48
        val placed = StripMath.place(t, w, 6f, { h -> h * cw + cw / 2 }, { if (it.day) 44f else 34f }, { h -> h * cw + 4f })
        assertTrue(placed.map { it.first }.containsAll(t.filter { it.day && it.hour > 0 }))
        // midnight 3 h after now: today's name gives way to tomorrow's (no overlap)
        assertTrue(placed.none { it.first.hour == 0 })
        // now at 9:00: today, 12, 18, tomorrow, 6, 12, 18, the day after ... all fit
        val hods9 = (0 until 48).map { (9 + it) % 24 }; val days9 = (0 until 48).map { (9 + it) / 24 }
        val t9 = StripMath.ticks(hods9, days9, 47, { d -> listOf("今天", "明天", "后天")[d] }, { h -> "$h 时" })
        val p9 = StripMath.place(t9, w, 6f, { h -> h * cw + cw / 2 }, { if (it.day) 44f else 34f }, { h -> h * cw + 4f })
        assertTrue(p9.any { it.first.hour == 0 } && p9.count { it.first.day } == 3)
        val spans = placed.map { (tk, l) -> l to l + if (tk.day) 44f else 34f }.sortedBy { it.first }
        for (i in 1 until spans.size) assertTrue("overlap $spans", spans[i].first >= spans[i - 1].second)
        assertTrue(spans.all { it.first >= 0f && it.second <= w })
    }

    // ---------------------------------------------------------------- hold repeat

    @Test fun holdStepsOnceThenFourPerSecondAndStopsOnRelease() {
        val h = HourHold()
        assertEquals(1, h.press(1, 0))
        var steps = 1
        var t = 0L
        while (t < 2_450) { t += 14; steps += h.tick(t) }
        // 1 on press + repeats at 450, 700, ... 2450 ms = 9 more
        assertEquals(10, steps)
        h.release()
        var after = 0
        while (t < 4_000) { t += 14; after += h.tick(t) }
        assertEquals(0, after)
        // the system tap of the same press is folded in; a later tap steps
        assertEquals(0, h.tap(1, 400))
        assertEquals(1, h.tap(1, 5_000))
        assertEquals(-1, h.tap(-1, 410))
    }

    private class Rig {
        val disp = StandardTestDispatcher()
        val scope = TestScope(disp)
        var up = 1000L
        var settings = Settings(seenHowTo = true)
        val flow = WeatherFlow(scope, disp, { 0L }, { up }, NoSource, NoStore, { settings }, { settings = it }, { true }, { "Asia/Shanghai" }, { false }, {}, {})
        fun frames(ms: Long) { val end = up + ms; while (up < end) { up += 14; flow.step() } }
        init { flow.debugSample(); flow.sceneReady(); flow.welcomeDone(); frames(100) }
    }

    private object NoSource : WeatherSource {
        override suspend fun forecast(c: City): String = error("offline")
        override suspend fun search(q: String, zh: Boolean): String = "{}"
        override suspend fun names(id: Long, zh: Boolean): String = "{}"
        override suspend fun currents(cs: List<City>): String = "[]"
    }

    private object NoStore : WeatherStore {
        override fun loadForecast(key: String): Pair<String, Long>? = null
        override fun saveForecast(key: String, json: String, fetchedAtMs: Long) {}
        override fun loadCities(): Pair<List<City>, Long?> = emptyList<City>() to null
        override fun saveCities(cities: List<City>, currentId: Long?) {}
    }

    /** M18: ◀ greyed at 0 h, ▶ at the end; held 4 h/s, stops on release; ◀ back to 0 h -> S02. */
    @Test fun flowStepsHoldsAndGreysAtTheEnds() {
        val r = Rig()
        val f = r.flow
        assertEquals(0, f.value.shown)
        assertFalse(f.canStep(-1)); assertTrue(f.canStep(1))
        assertFalse(f.stepHour(-1))
        f.stepHour(1); r.frames(400)
        assertEquals(1, f.value.shown); assertEquals(Phase.LATER, f.value.phase)
        // hold ▶ for 2 s: 1 + 6 repeats (450 ms delay, then every 250 ms)
        f.holdStart(1, "test"); r.frames(2_000)
        f.holdEnd("test")
        val held = f.value.let { r.frames(400); f.value.shown }
        assertTrue("held to $held", held in 7..9)
        r.frames(1_000)
        assertEquals(held, f.value.shown)
        // to the end: ▶ greys, a held ▶ stops by itself
        val end = f.value.slice.endHour
        f.debugScrub((end - 2).toFloat())
        f.holdStart(1, "test"); r.frames(3_000)
        assertEquals(end, f.value.shown)
        assertEquals(0, f.holding)
        assertFalse(f.canStep(1)); assertTrue(f.canStep(-1))
        // ◀ back to 0 h settles in S02
        f.debugScrub(2f)
        f.stepHour(-1); r.frames(300); f.stepHour(-1); r.frames(400)
        assertEquals(0, f.value.shown)
        assertEquals(Phase.NOW, f.value.phase)
        assertFalse(f.canStep(-1))
    }

    /** Readout / window / strip cursor follow the same shown hour through a held step (D38). */
    @Test fun holdKeepsOneHourSource() {
        val r = Rig()
        r.flow.holdStart(1, "test")
        repeat(100) { r.frames(14); assertEquals(r.flow.value.shown, r.flow.value.shown) }
        r.flow.holdEnd("test"); r.frames(500)
        assertEquals(r.flow.stepTarget(), r.flow.value.shown)
    }

    // ---------------------------------------------------------------- B24 white clouds / smoke

    @Test fun cloudsAndSmokeStayWhiteDayAndNight() {
        val s = Sample.slice()
        var minDay = 1f; var minNight = 1f
        for (h in 0..s.endHour) for (night in listOf(false, true, null)) {
            val p = SceneModel.at(s, h.toFloat(), night)
            for (look in listOf(WhiteLook.cloud(p.cloudShade, p.brightness), WhiteLook.look(WhiteLook.SMOKE, p.brightness), WhiteLook.look(WhiteLook.FOG, p.brightness))) {
                val v = WhiteLook.worstCase(look)
                if (p.isDay) minDay = minOf(minDay, v) else minNight = minOf(minNight, v)
                for (c in 0..2) assertTrue("emissive >= 40 % of base", look.emissive[c] >= 0.4f * look.base[c])
            }
        }
        println("B24 min final brightness day=$minDay night=$minNight")
        assertTrue("day $minDay", minDay >= 0.6f)
        assertTrue("night $minNight", minNight >= 0.35f)
        // every shade at the darkest day / night brightness the model can give
        for (shade in 0..3) {
            assertTrue(WhiteLook.worstCase(WhiteLook.cloud(shade, 0.68f)) >= 0.6f)
            assertTrue(WhiteLook.worstCase(WhiteLook.cloud(shade, 0.38f)) >= 0.35f)
        }
    }
}
