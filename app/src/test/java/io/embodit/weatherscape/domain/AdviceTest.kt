package io.embodit.weatherscape.domain

import io.embodit.weatherscape.domain.Fx.hour
import io.embodit.weatherscape.domain.Texts.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** M15: one case per advice rule, plus the remaining-hours window, the 20 % / 0.2 mm case and a null probability. */
class AdviceTest {
    private fun rule(s: Slice, h: Int = 0) = Texts.advice(s, h, true, TempUnit.C).first
    private fun zh(s: Slice, h: Int = 0) = Texts.advice(s, h, true, TempUnit.C).second
    private fun en(s: Slice, h: Int = 0) = Texts.advice(s, h, false, TempUnit.C).second

    @Test fun rainAllWindow() {
        val s = Fx.slice { hour(it, code = 61, pop = 90, precip = 2f) }
        assertEquals(Rule.RAIN_ALL, rule(s))
        assertEquals("这 12 小时一直有雨，伞别离手。", zh(s))
    }

    @Test fun rainStops() {
        val s = Fx.slice { hour(it, code = if (it < 4) 61 else 0, pop = if (it < 4) 90 else 5, precip = if (it < 4) 2f else 0f) }
        assertEquals(Rule.RAIN_STOPS, rule(s))
        assertTrue(zh(s).endsWith("停，等等再走。"))
    }

    @Test fun rainStarts() {
        val s = Fx.slice { hour(it, pop = if (it == 6) 70 else 10) }
        assertEquals(Rule.RAIN_STARTS, rule(s))
        assertTrue(zh(s), zh(s).contains("开始下，出门带伞"))
        assertTrue(en(s), en(s).startsWith("Rain starts"))
    }

    @Test fun showers30to49() {
        val s = Fx.slice { hour(it, pop = if (it == 3) 40 else 10) }
        assertEquals(Rule.SHOWERS, rule(s))
    }

    @Test fun pop20WithPoint2mmIsShowersLater() {
        // 20 % but 0.2 mm three hours from now: not "dry" (rule 5 "降水量和 > 0"); it is falling then, not now
        val s = Fx.slice { hour(it, pop = 20, precip = if (it == 3) 0.2f else 0f) }
        assertEquals(Rule.SHOWERS, rule(s))
        // the same hour as the ring position: it is falling now and stops after -> rule 3
        assertEquals(Rule.RAIN_STOPS, rule(s, 3))
    }

    @Test fun popNullCountsAs60WhenItFalls() {
        val s = Fx.slice { hour(it, pop = null, precip = if (it == 4) 0.5f else 0f) }
        assertEquals(60, s.at(4).popEff)
        assertEquals(Rule.RAIN_STARTS, rule(s))
        val dry = Fx.slice { hour(it, pop = null) }
        assertEquals(Rule.DRY, rule(dry))
    }

    @Test fun storm() {
        val s = Fx.slice { hour(it, code = if (it == 5) 95 else 0) }
        assertEquals(Rule.STORM, rule(s))
        assertTrue(zh(s).endsWith("有雷，早点回家。"))
    }

    @Test fun cold() {
        val s = Fx.slice { hour(it, temp = 4f, feels = if (it == 7) 1.6f else 4f) }
        assertEquals(Rule.COLD, rule(s))
        assertTrue(zh(s), zh(s).endsWith("2°，手套带上。"))
        assertTrue(Texts.advice(s, 0, false, TempUnit.F).second.startsWith("35°"))
    }

    @Test fun windy() {
        val s = Fx.slice { hour(it, wind = if (it == 2) 25f else 5f) }
        assertEquals(Rule.WINDY, rule(s))
    }

    @Test fun dry() {
        val s = Fx.slice()
        assertEquals(Rule.DRY, rule(s))
        assertEquals("接下来 12 小时都干的，伞放家里吧。", zh(s))
        assertEquals("Dry for the next 12 hours. Leave the umbrella.", en(s))
    }

    @Test fun windowShrinksNearTheEnd() {
        val s = Fx.slice(48)
        assertEquals("接下来 5 小时都干的，伞放家里吧。", zh(s, 43))
        val short = Fx.slice(20)
        assertEquals("接下来 3 小时都干的，伞放家里吧。", zh(short, 17))
    }

    @Test fun sampleDayEveryRuleHasASentence() {
        val s = Sample.slice()
        for (h in 0..47) { val (r, t) = Texts.advice(s, h, true, TempUnit.C); assertTrue("$h $r", t.isNotBlank() && t.endsWith("。")) }
        assertEquals(Rule.RAIN_STARTS, rule(s, 6))
        assertEquals(Rule.STORM, rule(s, 15))
    }
}
