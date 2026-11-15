package io.embodit.weatherscape.ui.scene.input

import io.embodit.weatherscape.domain.Ring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D53 drag-delta (QA #5 q5-1, headset "拨铜环不跟手"): SDK 6.1.9 `dragAmount` is one event's delta, so the
 * board input must add them up. Headset pattern: 60 events x 2 mm = 12 cm; reset (the next press) starts at 0.
 */
class DragTotalTest {
    @Test fun sixtyTwoMillimetreStepsTotalTwelveCentimetres() {
        val t = DragTotal()
        var last = Offset3M(0f, 0f, 0f)
        repeat(60) { last = t.add(Offset3M(0.002f, 0f, 0f)) }
        assertEquals(0.12f, last.x, 1e-4f)
        assertEquals(0f, last.y, 1e-6f)
        assertEquals(0f, last.z, 1e-6f)
        assertEquals(0.12f, last.length, 1e-4f)
        assertEquals(60, t.events)
    }

    @Test fun resetZeroesForTheNextPress() {
        val t = DragTotal()
        repeat(60) { t.add(Offset3M(0.002f, -0.001f, 0.0005f)) }
        t.reset()
        assertEquals(0f, t.x, 0f); assertEquals(0f, t.y, 0f); assertEquals(0f, t.z, 0f)
        assertEquals(0, t.events)
        val first = t.add(Offset3M(0.002f, 0f, 0f))
        assertEquals(0.002f, first.x, 1e-6f)
    }

    @Test fun mixedDirectionsCancel() {
        val t = DragTotal()
        repeat(30) { t.add(Offset3M(0.002f, 0f, 0f)) }
        repeat(30) { t.add(Offset3M(-0.002f, 0f, 0f)) }
        assertEquals(0f, t.x, 1e-5f)
    }

    /** Downstream: the ring reads the accumulated value, so 12 cm of hand-ray travel moves it ~4 h (3 cm / h). */
    @Test fun ringFollowsTheAccumulatedTravel() {
        val total = DragTotal()
        val r = Ring()
        r.dragStart(1f, 0)
        var ms = 0L
        repeat(60) { ms += 14; r.dragMove(total.add(Offset3M(0.002f, 0f, 0f)).x, ms) }
        repeat(40) { ms += 14; r.dragMove(total.x, ms) } // hold still: filter settles
        val expected = 0.12f * Ring.hoursPerMetre(1f)
        assertEquals(expected, r.pos, 0.15f)
        assertTrue("ring must travel > 3 h, got ${r.pos}", r.pos > 3f)
    }
}
