package io.embodit.weatherscape.domain

import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.6 (QA #6 q6-1: the v0.1.4 Tripo cumulus hid the tree tag "东北风 2 级 · 8 km/h" in 4 of 5 shots). Like the smoke
 * (q1-1), rain and snow (q3-2), the clouds now make way for an ask tag: while a tag is up, a cloud whose box meets the
 * tag's zone, or sits on a sightline from any user eye to the tag's corners / centre, shrinks away (0.15 s) and grows back
 * when the tag goes. The scene and the unit test call the same [blocks].
 */
object CloudYield {
    /** Room around the cloud box: the 0.8 s grow-in overshoot and the storm drop (8 mm). */
    const val MARGIN = 0.010f
    /** Shrink time constant (s): gone in ~0.15 s, like the smoke and the flakes. */
    const val TAU_OUT = 0.03f
    const val TAU_IN = 0.15f

    /**
     * User eyes (layout units): the four design eyes (seated, level, 30° left / right) plus straight-ahead eyes 10 / 15 /
     * 20° above the volume centre at the system's 1.75 m and at 1.2 m. The emulator's default eye sees the diorama ~15°
     * from above (QA #6 frame: the ring ellipse 245 / 930 px), which is where the tree tag was hidden. A wider fan
     * (0–40°, ±30° at both distances) made 57 % of the clouds vanish for every tag, so the fan stays on real eyes.
     */
    val EYES: List<V3> = buildList {
        addAll(Layout.EYES)
        for (dist in listOf(1.75f, 1.2f)) for (elev in listOf(10f, 15f, 20f)) for (yaw in listOf(0f)) {
            val d = dist / Layout.SCALE
            val e = Layout.deg(elev); val y = Layout.deg(yaw)
            add(V3(d * cos(e) * sin(y), d * sin(e), d * cos(e) * cos(y)))
        }
    }

    /** Cloud box for the model's bottom centre [c] at scale [s] (fit box CLOUD_W x CLOUD_H x CLOUD_D), with the storm drop. */
    fun bounds(c: V3, s: Float = 1f): Aabb =
        Aabb(c + V3(-Layout.CLOUD_W / 2 * s, -0.008f, -Layout.CLOUD_D / 2 * s), c + V3(Layout.CLOUD_W / 2 * s, Layout.CLOUD_H * s, Layout.CLOUD_D / 2 * s))

    /** The tag's corners + centre on its front face, over its whole 1 cm rise (PrecipYield.tagZone). */
    fun tagPoints(tag: Aabb): List<V3> {
        val z = PrecipYield.tagZone(tag)
        val f = z.hi.z
        return listOf(V3(z.lo.x, z.lo.y, f), V3(z.hi.x, z.lo.y, f), V3(z.lo.x, z.hi.y, f), V3(z.hi.x, z.hi.y, f),
            V3((z.lo.x + z.hi.x) / 2, (z.lo.y + z.hi.y) / 2, f), V3(z.lo.x, (z.lo.y + z.hi.y) / 2, f), V3(z.hi.x, (z.lo.y + z.hi.y) / 2, f))
    }

    /** True when the cloud box [cloud] meets the tag zone or hides any tag point from any eye. */
    fun blocks(cloud: Aabb, tag: Aabb): Boolean {
        val g = cloud.grow(MARGIN)
        if (g.intersects(PrecipYield.tagZone(tag))) return true
        val pts = tagPoints(tag)
        for (eye in EYES) for (p in pts) if (segmentHits(eye, p, g)) return true
        return false
    }

    /** Segment a -> b crosses [box] (slab test). */
    fun segmentHits(a: V3, b: V3, box: Aabb): Boolean {
        var t0 = 0f; var t1 = 1f
        val o = floatArrayOf(a.x, a.y, a.z)
        val d = floatArrayOf(b.x - a.x, b.y - a.y, b.z - a.z)
        val lo = floatArrayOf(box.lo.x, box.lo.y, box.lo.z)
        val hi = floatArrayOf(box.hi.x, box.hi.y, box.hi.z)
        for (k in 0 until 3) {
            if (kotlin.math.abs(d[k]) < 1e-7f) { if (o[k] < lo[k] || o[k] > hi[k]) return false; continue }
            var ta = (lo[k] - o[k]) / d[k]; var tb = (hi[k] - o[k]) / d[k]
            if (ta > tb) { val x = ta; ta = tb; tb = x }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    /** Yield target for one cloud: 0 = make way for the tag, 1 = show. */
    fun target(cloudAt: V3, tag: Aabb?): Float = if (tag != null && blocks(bounds(cloudAt), tag)) 0f else 1f
}
