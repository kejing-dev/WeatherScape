package io.embodit.weatherscape.ui.control

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Weather-scape v0.1.6 (QA #6 q6-2: the 4-stop sound segments changed on a tap but not on a drag): the sound row is a
 * 4-stop slider (关 / 低 / 中 / 高) on this logic, ported from tide-tank. Original note: the volume slider's logic (B20; user 2026-11-14 「音量条点了没反应」): the Slider is bound to a free Float
 * [pos], not to the stop, so a tap or a short drag is never swallowed. While the finger moves the nearest stop is applied
 * live; on release the thumb snaps to a stop, and a short drag (>= [NUDGE] of a stop from where it started) that did not reach the next stop
 * still moves one stop in its direction (a tap goes to the nearest stop). Pure Kotlin: VolumeDragTest drives it like the Slider does (onValueChange* then
 * onValueChangeFinished).
 */
class VolumeDrag(level: Int, private val stops: Int = 3) {
    var pos: Float = level.toFloat(); private set
    var level: Int = level; private set
    private var from: Int? = null
    private var moves = 0
    private var first = 0f

    /** The level changed elsewhere (debug broadcast, prefs): follow it unless a drag is going on. */
    fun sync(external: Int) { if (from == null && external != level) { level = external; pos = external.toFloat() } }

    /** Slider.onValueChange: returns the new level when it changed. */
    fun move(v: Float): Int? {
        if (from == null) { from = level; moves = 0 }
        moves++
        pos = v.coerceIn(0f, (stops - 1).toFloat())
        if (moves == 1) first = pos
        val near = nearest(pos)
        if (near == level) return null
        level = near
        return near
    }

    /** Slider.onValueChangeFinished: snaps the thumb; returns the new level when it changed. */
    fun finish(): Int? {
        val start = from ?: level
        // a tap is one jump to the touch point (plus maybe a little jitter): it goes to the nearest stop. A drag is measured
        // from its own first value, so a tap at 52 % with a wobble does not count as a drag toward 100 %.
        val travel = pos - first
        val dragged = moves > 1 && abs(travel) >= NUDGE
        from = null; moves = 0
        var end = nearest(pos)
        if (dragged && end == start) end = (start + if (travel > 0) 1 else -1).coerceIn(0, stops - 1)
        pos = end.toFloat()
        if (end == level) return null
        level = end
        return end
    }

    private fun nearest(v: Float) = v.roundToInt().coerceIn(0, stops - 1)

    companion object {
        /** A drag of 3 % of one stop (1.5 % of the bar, ~3 dp after the touch slop on the 800 dp window) counts as "move one stop". */
        const val NUDGE = 0.03f
    }
}
