package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sign

/** 1€ filter (Casiez et al. 2012) on one value; [t] in seconds. */
class OneEuro(private val minCutoff: Float = 1f, private val beta: Float = 0.007f, private val dCutoff: Float = 1f) {
    private var x: Float? = null
    private var dx = 0f
    private var lastT = 0f

    private fun alpha(cutoff: Float, dt: Float): Float { val tau = 1f / (2f * PI.toFloat() * cutoff); return 1f / (1f + tau / dt) }

    fun reset() { x = null; dx = 0f }

    fun filter(v: Float, t: Float): Float {
        val prev = x ?: run { x = v; lastT = t; return v }
        val dt = (t - lastT).coerceAtLeast(1e-3f)
        lastT = t
        val rawDx = (v - prev) / dt
        dx += alpha(dCutoff, dt) * (rawDx - dx)
        val cutoff = minCutoff + beta * abs(dx)
        val out = prev + alpha(cutoff, dt) * (v - prev)
        x = out
        return out
    }
}

/**
 * The brass time ring (design input.ringDrag + animation): drag (1€-filtered hand travel x gain / R), release with
 * inertia (> 2 h/s, damping 4 /s, at most 6 h/s), a 0.25 s ease-out snap to the nearest whole hour, hard stops at 0 and
 * the end hour (a 2° rebound, no error), and the "back to now" / strip jump animations (ease-in-out, always the
 * straight way back). Pure: the caller passes the clock (ms). Hours are floats; [shown] is the whole hour the readout,
 * the window and the strip cursor show, with a +-0.5 h + [HYST] hysteresis so jitter never flips it.
 */
class Ring {
    enum class Mode { IDLE, DRAG, INERTIA, SNAP, ANIM }

    companion object {
        const val DEG_PER_H = 330f / 48f
        const val R = 0.25f
        const val INERTIA_MIN = 2f
        const val INERTIA_MAX = 6f
        const val DAMPING = 4f
        const val SNAP_MS = 250L
        const val HYST = 0.12f
        const val BOUNCE_DEG = 2f
        const val BOUNCE_MS = 300L

        /** Hours per metre of hand travel: Δθ = s × g / R (rad) -> hours. */
        fun hoursPerMetre(gain: Float): Float = gain / R * (180f / PI.toFloat()) / DEG_PER_H

        /** "Back to now" / strip jumps: <= 12 h in 0.6 s, longer in proportion, at most 1.2 s. */
        fun animMs(fromH: Float, toH: Float): Long {
            val d = abs(fromH - toH)
            return (if (d <= 12f) 600f else (600f * d / 12f).coerceAtMost(1200f)).toLong()
        }

        private fun easeInOut(t: Float) = if (t < 0.5f) 2 * t * t else 1 - (-2 * t + 2) * (-2 * t + 2) / 2
        private fun easeOut(t: Float) = 1 - (1 - t) * (1 - t) * (1 - t)
    }

    var mode = Mode.IDLE
        private set
    var pos = 0f
        private set
    var end = 47
        private set
    var shown = 0
        private set
    /** Visual rebound (hours) at an end stop; added to [pos] only for drawing. */
    var bounce = 0f
        private set

    private val filter = OneEuro(1f, 0.007f)
    private var dragStartPos = 0f
    private var gainHpm = hoursPerMetre(1f)
    private var vel = 0f
    private var lastMs = 0L
    private var hist = ArrayDeque<Pair<Long, Float>>()
    private var snapFrom = 0f
    private var snapTo = 0f
    private var snapAt = 0L
    private var snapMs = SNAP_MS
    private var bounceAt = -1L
    private var bounceSign = 0f
    /** Set when the ring reached its target at rest (the caller reads + clears it). */
    var settledAt: Long = -1L

    fun setEnd(e: Int) {
        end = e.coerceIn(0, 47)
        if (pos > end) { pos = end.toFloat(); shown = end }
    }

    /** Debug / restore: put the ring on [h] at rest. */
    fun jump(h: Float) { pos = h.coerceIn(0f, end.toFloat()); shown = pos.roundToInt(); mode = Mode.IDLE; vel = 0f }

    /** Absolute-time shift when the city's local hour rolls over while looking at a later hour (design weatherModel.time). */
    fun shift(dh: Float) { pos = (pos + dh).coerceIn(0f, end.toFloat()); snapTo = (snapTo + dh).coerceIn(0f, end.toFloat()); shown = pos.roundToInt() }

    fun dragStart(gain: Float, ms: Long) {
        mode = Mode.DRAG
        gainHpm = hoursPerMetre(gain)
        dragStartPos = pos
        filter.reset()
        hist.clear()
        vel = 0f
        lastMs = ms
    }

    /** [travelM] = the hand's horizontal travel since the press (volume +X = right = later). */
    fun dragMove(travelM: Float, ms: Long) {
        if (mode != Mode.DRAG) return
        val f = filter.filter(travelM, ms / 1000f)
        val raw = dragStartPos + f * gainHpm
        val clamped = raw.coerceIn(0f, end.toFloat())
        if (clamped != raw && abs(pos - clamped) > 1e-4f) startBounce(sign(raw - clamped), ms)
        pos = clamped
        hist.addLast(ms to pos)
        while (hist.size > 2 && ms - hist.first().first > 120) hist.removeFirst()
        lastMs = ms
        updateShown()
    }

    fun dragEnd(ms: Long) {
        if (mode != Mode.DRAG) return
        val v = if (hist.size >= 2) {
            val (t0, p0) = hist.first(); val (t1, p1) = hist.last()
            if (t1 > t0) (p1 - p0) / ((t1 - t0) / 1000f) else 0f
        } else 0f
        if (abs(v) > INERTIA_MIN) {
            vel = v.coerceIn(-INERTIA_MAX, INERTIA_MAX)
            mode = Mode.INERTIA
            lastMs = ms
        } else startSnap(ms)
    }

    fun dragCancel(ms: Long) { if (mode == Mode.DRAG) startSnap(ms) }

    /** Animate to [h] (back to now = 0, strip tap = that hour). */
    fun animateTo(h: Int, ms: Long, durMs: Long? = null) {
        val to = h.coerceIn(0, end).toFloat()
        snapFrom = pos; snapTo = to; snapAt = ms; snapMs = durMs ?: animMs(pos, to)
        mode = Mode.ANIM
        vel = 0f
    }

    private fun startSnap(ms: Long) {
        snapFrom = pos
        snapTo = pos.roundToInt().coerceIn(0, end).toFloat()
        snapAt = ms; snapMs = SNAP_MS
        mode = Mode.SNAP
    }

    private fun startBounce(s: Float, ms: Long) { bounceAt = ms; bounceSign = s }

    private fun updateShown() {
        // hysteresis: the shown hour moves only when pos is 0.5 + HYST past it (or lands exactly on a whole hour)
        val d = pos - shown
        if (abs(d) >= 0.5f + HYST || (mode != Mode.DRAG && abs(pos - pos.roundToInt()) < 1e-3f)) shown = pos.roundToInt().coerceIn(0, end)
    }

    /** Advance to [ms]. Returns the drawn position (pos + rebound). */
    fun step(ms: Long): Float {
        val dt = ((ms - lastMs).coerceIn(0, 100)) / 1000f
        lastMs = ms
        when (mode) {
            Mode.INERTIA -> {
                pos += vel * dt
                vel *= exp(-DAMPING * dt)
                if (pos <= 0f || pos >= end) {
                    startBounce(sign(vel), ms)
                    pos = pos.coerceIn(0f, end.toFloat())
                    startSnap(ms)
                } else if (abs(vel) < 1.2f) {
                    // final 0.25 s: ease to the whole hour ahead in the travel direction
                    snapFrom = pos
                    snapTo = (if (vel > 0) floor(pos) + 1f else floor(pos)).coerceIn(0f, end.toFloat())
                    if (abs(snapTo - pos) > 0.75f) snapTo = pos.roundToInt().toFloat()
                    snapAt = ms; snapMs = SNAP_MS; mode = Mode.SNAP
                }
            }
            Mode.SNAP, Mode.ANIM -> {
                val t = ((ms - snapAt).toFloat() / snapMs).coerceIn(0f, 1f)
                val k = if (mode == Mode.SNAP) easeOut(t) else easeInOut(t)
                pos = snapFrom + (snapTo - snapFrom) * k
                if (t >= 1f) { pos = snapTo; mode = Mode.IDLE; settledAt = ms }
            }
            else -> Unit
        }
        updateShown()
        bounce = if (bounceAt >= 0 && ms - bounceAt < BOUNCE_MS) {
            val k = (ms - bounceAt).toFloat() / BOUNCE_MS
            bounceSign * BOUNCE_DEG / DEG_PER_H * kotlin.math.sin(PI.toFloat() * k) * (1 - k)
        } else 0f
        return pos + bounce
    }

    val moving: Boolean get() = mode != Mode.IDLE

    /** The whole hour the ring is heading for (an animation's target, else the shown hour): ◀ / ▶ chain from here. */
    val target: Int get() = if (mode == Mode.ANIM || mode == Mode.SNAP) snapTo.roundToInt().coerceIn(0, end) else shown
    val endHour: Int get() = end
}
