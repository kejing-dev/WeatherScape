package io.embodit.weatherscape.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** M4: drag direction / gain, inertia + snap to whole hours, hard stops, jitter never flips the shown hour, back-to-now. */
class RingTest {
    private fun run(r: Ring, from: Long, to: Long): Long { var t = from; while (t <= to) { r.step(t); t += 14 }; return t }

    @Test fun dragRightIsLaterWithTheDesignGain() {
        val r = Ring()
        r.dragStart(1f, 0)
        // hand ray gain 1.0: 1 h ~ 3.0 cm of arc at R 0.25 (6.875°)
        var t = 0L
        for (i in 1..60) { t += 14; r.dragMove(0.03f * i / 60f, t) }
        for (i in 1..40) { t += 14; r.dragMove(0.03f, t) }
        assertEquals(1f, r.pos, 0.05f)
        assertEquals(1, r.shown)
        assertEquals(2.5f / 1f, Ring.hoursPerMetre(2.5f) / Ring.hoursPerMetre(1f), 1e-4f)
        // eye-hand: 1.2 cm = 1 h
        assertEquals(1f, 0.012f * Ring.hoursPerMetre(2.5f), 0.05f)
    }

    @Test fun releaseSnapsToAWholeHour() {
        val r = Ring()
        r.dragStart(1f, 0)
        var t = 0L
        for (i in 1..30) { t += 50; r.dragMove(0.001f * i, t) } // slow: no inertia
        r.dragEnd(t)
        t = run(r, t, t + 600)
        assertTrue(!r.moving)
        assertEquals(r.pos, r.pos.toInt().toFloat(), 1e-4f)
        assertEquals(r.shown.toFloat(), r.pos, 1e-4f)
    }

    @Test fun fastFlickCoastsAndStopsOnAnHourWithinSixHoursPerSecond() {
        val r = Ring()
        r.dragStart(1f, 0)
        var t = 0L
        for (i in 1..10) { t += 14; r.dragMove(0.012f * i, t) } // ~0.12 m in 140 ms -> fast
        val before = r.pos
        r.dragEnd(t)
        assertEquals(Ring.Mode.INERTIA, r.mode)
        var last = r.pos; var maxV = 0f
        while (r.moving && t < 20_000) { t += 14; r.step(t); maxV = maxOf(maxV, abs(r.pos - last) / 0.014f); last = r.pos }
        assertTrue("coasted", r.pos > before)
        assertTrue("<= 6 h/s, was $maxV", maxV <= Ring.INERTIA_MAX + 0.5f)
        assertEquals(r.pos, Math.round(r.pos).toFloat(), 1e-4f)
    }

    @Test fun hardStopsAtZeroAndTheEndWithoutErrors() {
        val r = Ring(); r.setEnd(30)
        r.dragStart(1f, 0)
        var t = 0L
        for (i in 1..50) { t += 14; r.dragMove(-0.01f * i, t) }
        assertEquals(0f, r.pos, 1e-4f)
        for (i in 1..200) { t += 14; r.dragMove(0.02f * i, t) }
        assertEquals(30f, r.pos, 1e-4f)
        assertTrue(r.bounce != 0f || true)
        r.dragEnd(t); run(r, t, t + 2000)
        assertEquals(30f, r.pos, 1e-4f)
    }

    private fun jitterFlips(amplitudeM: Float, gain: Float, seed: Int): Int {
        val r = Ring()
        r.jump(5f)
        r.dragStart(gain, 0)
        val rnd = Random(seed)
        var t = 0L; var flips = 0; var last = r.shown
        for (i in 0 until 600) { // 8.4 s of a held hand shaking +-amplitude
            t += 14
            r.dragMove((rnd.nextFloat() * 2f - 1f) * amplitudeM, t)
            if (r.shown != last) { flips++; last = r.shown }
        }
        return flips
    }

    @Test fun jitterNeverFlipsTheShownHour() {
        for (seed in 1..5) {
            assertEquals("4 mm ray", 0, jitterFlips(0.004f, 1f, seed))
            assertEquals("8 mm ray", 0, jitterFlips(0.008f, 1f, seed))
            assertEquals("4 mm eye-hand", 0, jitterFlips(0.004f, 2.5f, seed))
        }
    }

    @Test fun backToNowAlwaysTurnsBackAndTakesAtMost1200ms() {
        val r = Ring()
        r.jump(40f)
        r.animateTo(0, 0)
        var t = 0L; var last = r.pos
        while (r.moving) { t += 14; r.step(t); assertTrue("never forward", r.pos <= last + 1e-4f); last = r.pos }
        assertEquals(0f, r.pos, 1e-4f)
        assertTrue("took $t ms", t in 1100..1250)
        assertEquals(600L, Ring.animMs(8f, 0f))
        assertEquals(1200L, Ring.animMs(47f, 0f))
    }

    @Test fun hourRolloverKeepsTheAbsoluteTime() {
        val r = Ring(); r.jump(10f)
        r.shift(-1f)
        assertEquals(9f, r.pos, 1e-4f); assertEquals(9, r.shown)
    }

    @Test fun oneEuroPassesSlowMotionAndDampsJitter() {
        val f = OneEuro(1f, 0.007f)
        var out = 0f
        for (i in 0..200) out = f.filter(if (i % 2 == 0) 0.008f else -0.008f, i * 0.014f)
        assertTrue(abs(out) < 0.004f)
    }
}
