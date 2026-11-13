package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Axis-aligned box (metres, volume frame). */
data class Aabb(val lo: V3, val hi: V3) {
    fun inside(o: Aabb) = lo.x >= o.lo.x && lo.y >= o.lo.y && lo.z >= o.lo.z && hi.x <= o.hi.x && hi.y <= o.hi.y && hi.z <= o.hi.z
    fun intersects(o: Aabb) = lo.x < o.hi.x && hi.x > o.lo.x && lo.y < o.hi.y && hi.y > o.lo.y && lo.z < o.hi.z && hi.z > o.lo.z
    fun grow(d: Float) = Aabb(lo - V3(d, d, d), hi + V3(d, d, d))
    override fun toString() = "[$lo..$hi]"

    companion object {
        fun of(points: List<V3>) = Aabb(V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
        fun centred(c: V3, size: V3) = Aabb(c - size * 0.5f, c + size * 0.5f)
    }
}

/**
 * Every metre of the volume (design container.volume.math + spatialLayout). Origin = volume centre, +X right (west),
 * +Y up, +Z toward the user (north). The volume is 0.66 x 0.54 x 0.68 m.
 */
object Layout {
    val VOLUME = Aabb(V3(-0.33f, -0.27f, -0.34f), V3(0.33f, 0.27f, 0.34f))
    /** Everything stays inside the volume shrunk by 1 cm (M5). */
    val SAFE = VOLUME.grow(-0.01f)

    // plinth: walnut drum Ø 0.40, y -0.26 .. -0.19
    const val PLINTH_R = 0.20f
    const val PLINTH_Y0 = -0.26f
    const val PLINTH_Y1 = -0.19f
    const val TOP = -0.19f

    // ring: flat brass band, inner R 0.215, outer R 0.280, 2.5 cm thick, centre y -0.230
    const val RING_IN = 0.215f
    const val RING_OUT = 0.280f
    const val RING_H = 0.025f
    const val RING_Y = -0.230f
    const val RING_TOP = RING_Y + RING_H / 2
    const val CHAMFER = 0.003f
    /** Ring collider (design input.ringDrag): r 0.205..0.290, y -0.258..-0.203. */
    const val RING_HIT_IN = 0.205f
    const val RING_HIT_OUT = 0.290f
    const val RING_HIT_Y0 = -0.258f
    const val RING_HIT_Y1 = -0.203f
    const val RING_HIT_SEGMENTS = 32

    // readout: brass case x -0.15..+0.15, y -0.260..-0.2175, z 0.305..0.330, face leaning back 25°
    val READOUT_C = V3(0f, -0.2375f, 0.3145f)
    const val READOUT_W = 0.30f
    const val READOUT_H = 0.0425f
    const val READOUT_T = 0.012f
    const val READOUT_TILT_DEG = 25f
    const val FACE_W = 0.275f
    const val FACE_H = 0.036f
    /** Pointer: from the readout's top back edge 6 cm back to z 0.245, its underside 3 mm above the ring top. */
    val POINTER_TIP = V3(0f, RING_TOP + 0.003f, 0.245f)
    // the "Panel" knob right of the readout: 5.5 cm visible, collider 6.5 x 6.5 cm
    val KNOB_C = V3(0.1825f, -0.230f, 0.315f)
    const val KNOB_R = 0.0255f
    const val KNOB_T = 0.02f
    const val KNOB_HIT = 0.065f
    const val KNOB_HIT_H = 0.062f

    // island + models (calibrated in ModelCalib)
    const val ISLAND_R = 0.19f

    // sky: clouds y +0.04 .. +0.18 inside r <= 0.22; at most 6 clouds, each <= 0.14 x 0.06 x 0.10
    const val CLOUD_Y0 = 0.04f
    const val CLOUD_Y1 = 0.18f
    const val CLOUD_R = 0.22f
    const val CLOUD_W = 0.14f
    const val CLOUD_H = 0.06f
    const val CLOUD_D = 0.10f
    /** Sky collider: the cloud layer grown 1 cm (one box over every cloud position). */
    val SKY_HIT = Aabb(V3(-CLOUD_R - 0.01f, CLOUD_Y0 - 0.01f, -CLOUD_R - 0.01f), V3(CLOUD_R + 0.01f, CLOUD_Y1 + 0.01f, CLOUD_R + 0.01f))

    // rain / snow: a cylinder R 0.14 over the island centre, bottom on the island, top at the cloud base, tilt <= 12°
    const val PRECIP_R = 0.14f
    const val PRECIP_Y0 = -0.155f
    const val PRECIP_Y1 = 0.04f
    const val PRECIP_TILT_MAX_DEG = 12f
    /** Rain lattice: drops on each strand every 5 cm so a block moved by (offset mod 5 cm) falls without a seam. */
    const val RAIN_STEP = 0.05f
    const val SNOW_STEP = 0.05f

    // sun / moon: arc centre (0, -0.15, -0.12), R 0.30, in the z = -0.12 plane; sunrise at x -0.30 (east)
    val ARC_C = V3(0f, -0.15f, -0.12f)
    const val ARC_R = 0.30f
    const val SUN_R = 0.015f
    const val MOON_R = 0.011f

    // tags: <= 0.16 x 0.04, 3 cm above the tapped thing, turned only about the vertical
    const val TAG_W = 0.16f
    const val TAG_H = 0.04f
    const val TAG_GAP = 0.03f

    /** Design eyes (spatialLayout.eyePositions): seated, level, 30° left / right. */
    val EYES = listOf(V3(0f, 0.35f, 1.75f), V3(0f, 0f, 1.75f), V3(-0.88f, 0.35f, 1.52f), V3(0.88f, 0.35f, 1.52f))

    fun deg(d: Float) = d * PI.toFloat() / 180f

    /**
     * Ring-local point at angle [a] (radians about +Y): (r sin a, y, r cos a), so a = 0 is the front (+Z) and turning the
     * ring by +θ moves the front toward +X (right = later).
     */
    fun ringPoint(a: Float, r: Float, y: Float) = V3(r * sin(a), y, r * cos(a))

    /** Ring-local angle of hour [h]'s tick; the ring turned by pos x DEG_PER_H brings hour pos under the pointer. */
    fun tickAngle(h: Float) = -deg(h * Ring.DEG_PER_H)

    /** The stop pin's ring-local angle: 15° past the end hour's +1 slot (design: 15° from the "now" line at the end). */
    fun pinAngle(end: Int) = -deg((end + 1) * Ring.DEG_PER_H + 15f)
    const val PIN_R = 0.2475f
    const val PIN_H = 0.012f
    const val PIN_D = 0.008f

    /** Sun / moon on the arc: k = 0 at rise (east, -X), 1 at set (west, +X). */
    fun onArc(k: Float, r: Float = ARC_R): V3 {
        val a = PI.toFloat() * (1f - k.coerceIn(0f, 1f))
        return V3(r * cos(a), ARC_C.y + r * sin(a), ARC_C.z)
    }

    /** Pointer box (the triangle's bounds), readout case box, knob box. */
    val POINTER_BOX = Aabb(V3(-0.006f, RING_TOP + 0.003f, 0.245f), V3(0.006f, RING_TOP + 0.009f, 0.307f))
    val READOUT_BOX: Aabb get() {
        val t = deg(READOUT_TILT_DEG)
        val hz = READOUT_H / 2 * sin(t) + READOUT_T / 2 * cos(t)
        val hy = READOUT_H / 2 * cos(t) + READOUT_T / 2 * sin(t)
        return Aabb(V3(-READOUT_W / 2, READOUT_C.y - hy, READOUT_C.z - hz), V3(READOUT_W / 2, READOUT_C.y + hy, READOUT_C.z + hz))
    }
    val KNOB_BOX = Aabb(V3(KNOB_C.x - KNOB_R, KNOB_C.y - KNOB_R, KNOB_C.z - KNOB_T / 2), V3(KNOB_C.x + KNOB_R, KNOB_C.y + KNOB_R, KNOB_C.z + KNOB_T / 2))
    val KNOB_HIT_BOX = Aabb(V3(KNOB_C.x - KNOB_HIT / 2, KNOB_C.y + 0.001f - KNOB_HIT_H / 2, 0.300f), V3(KNOB_C.x + KNOB_HIT / 2, KNOB_C.y + 0.001f + KNOB_HIT_H / 2, 0.330f))
    val RING_BOX = Aabb(V3(-RING_OUT, RING_Y - RING_H / 2, -RING_OUT), V3(RING_OUT, RING_TOP + PIN_H, RING_OUT))
    val PLINTH_BOX = Aabb(V3(-PLINTH_R, PLINTH_Y0, -PLINTH_R), V3(PLINTH_R, PLINTH_Y1, PLINTH_R))

    /** Stop pin box at ring position [pos] with end hour [end] (world, the ring turned by pos). */
    fun pinBox(pos: Float, end: Int): Aabb {
        val a = pinAngle(end) + deg(pos * Ring.DEG_PER_H)
        return Aabb.centred(ringPoint(a, PIN_R, RING_TOP + PIN_H / 2), V3(PIN_D, PIN_H, PIN_D))
    }

    /** Rain / snow cylinder bounds at tilt [tiltDeg] toward [dir] (unit xz): bottom fixed, top shifted downwind. */
    fun precipBox(tiltDeg: Float, dirX: Float, dirZ: Float, stepAbove: Float = RAIN_STEP): Aabb {
        val h = PRECIP_Y1 - PRECIP_Y0 + stepAbove
        val off = tan(deg(tiltDeg)) * h
        val pts = ArrayList<V3>()
        for (k in 0 until 16) {
            val a = 2 * PI.toFloat() * k / 16
            val x = PRECIP_R * cos(a); val z = PRECIP_R * sin(a)
            pts += V3(x, PRECIP_Y0 - stepAbove, z)
            pts += V3(x + dirX * off, PRECIP_Y0 + h - stepAbove, z + dirZ * off)
        }
        return Aabb.of(pts)
    }

    /** Tag box above [anchorTop] (centre x/z of the tapped thing, its top y). */
    fun tagBox(at: V3): Aabb = Aabb(V3(at.x - TAG_W / 2, at.y + TAG_GAP, at.z - 0.002f), V3(at.x + TAG_W / 2, at.y + TAG_GAP + TAG_H, at.z + 0.002f))

    /** Distance helper. */
    fun hyp(x: Float, z: Float) = sqrt(x * x + z * z)
}
