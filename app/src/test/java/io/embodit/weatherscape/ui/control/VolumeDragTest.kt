package io.embodit.weatherscape.ui.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v0.1.6 (QA #6 q6-2, B20): the 4-stop sound slider changes on taps (0 / 33 / 67 / 100 %) and on drags, both ways. */
class VolumeDragTest {
    private fun tap(d: VolumeDrag, frac: Float): Int { d.move(frac * 3f); d.finish(); return d.level }

    @Test fun tapsReachEveryStopFromEveryStop() {
        for (start in 0..3) for ((frac, want) in listOf(0f to 0, 0.33f to 1, 0.5f to 2, 0.67f to 2, 1f to 3)) {
            val d = VolumeDrag(start, stops = 4)
            assertEquals("from $start tap ${frac * 100} %", want, tap(d, frac))
            assertEquals(want.toFloat(), d.pos, 0f)
        }
    }

    /** QA #6 swipes: off -> high, high -> low, mid -> off, low -> high (each was ignored by the segments). */
    @Test fun longDragsLandWhereTheyEnd() {
        for ((start, end) in listOf(0 to 3, 3 to 1, 2 to 0, 1 to 3)) {
            val d = VolumeDrag(start, stops = 4)
            var live = start
            val steps = 12
            for (k in 1..steps) { d.move(start + (end - start) * k / steps.toFloat())?.let { live = it } }
            assertEquals("live level during the drag $start -> $end", end, live)
            d.finish()
            assertEquals("$start -> $end", end, d.level)
        }
    }

    @Test fun shortDragsMoveOneStopAndAWobbleDoesNot() {
        for (start in 0..3) for (dir in listOf(-1, 1)) {
            val d = VolumeDrag(start, stops = 4)
            d.move(start + dir * 0.1f); d.move(start + dir * 0.2f); d.finish()
            assertEquals("from $start drag $dir", (start + dir).coerceIn(0, 3), d.level)
        }
        val w = VolumeDrag(2, stops = 4); w.move(2.01f); w.move(1.995f)
        assertNull(w.finish()); assertEquals(2, w.level)
    }

    /** The level set elsewhere (mute on the weather page, debug "sound") moves the thumb. */
    @Test fun followsOutsideChanges() {
        val d = VolumeDrag(2, stops = 4)
        d.sync(0); assertEquals(0, d.level); assertEquals(0f, d.pos, 0f)
    }
}
