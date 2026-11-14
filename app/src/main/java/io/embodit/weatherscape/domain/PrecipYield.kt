package io.embodit.weatherscape.domain

/**
 * v0.1.3 (QA #3 q3-2): rain streaks crossed the tree / house tag and hid "体感 12°" / "16 km/h". Like the smoke (q1-1),
 * the rain and snow now make way for an ask tag: while a tag is up, every drop / flake that can pass in front of the
 * tag's box (grown by a margin) is left out of the sheets; the rest keep falling above, below and around it. Strands behind the tag are hidden by the
 * opaque plate itself. The scene builds the holed sheets from the same functions the unit test checks.
 */
object PrecipYield {
    /** Extra room around the tag: streak half width 1 mm, flake jitter + sway 9 mm, the tag's 1 cm rise. */
    const val RAIN_MARGIN = 0.006f
    const val SNOW_MARGIN = 0.014f

    /** The sheet turn for a tilt toward downwind (the same quaternion the scene applies). */
    fun tiltQ(tiltDeg: Float, downX: Float, downZ: Float): Q4 {
        if (tiltDeg <= 0.1f) return Q4.ID
        val axis = V3.UP.cross(V3(downX, 0f, downZ)).let { if (it.length < 1e-4f) V3(1f, 0f, 0f) else it }
        return Q4.axis(axis, Layout.deg(tiltDeg))
    }

    /** The box a tag may occupy (it rises 1 cm into place), the area no streak may cross. */
    fun tagZone(tag: Aabb): Aabb = Aabb(V3(tag.lo.x, tag.lo.y - 0.011f, tag.lo.z), tag.hi)

    /**
     * True when a drop / flake of the sheet at sheet-local (x, y0 .. y1, z) can cross in front of [zone] (grown by
     * [margin]) at tilt [q]. The sheet hangs from (0, PRECIP_Y0, 0) and slides down its own axis by up to one [step],
     * so the drop sweeps local y (y0 - step) .. y1. "In front" = at or nearer than the tag's back face (the tag faces +Z);
     * drops behind it are hidden by the opaque plate.
     */
    fun blocks(x: Float, y0: Float, y1: Float, z: Float, step: Float, q: Q4, zone: Aabb, margin: Float): Boolean {
        val lo = zone.lo - V3(margin, margin, margin); val hi = zone.hi + V3(margin, margin, margin)
        var y = y0 - step
        while (y <= y1 + 1e-4f) {
            val p = V3(0f, Layout.PRECIP_Y0, 0f) + q.rotate(V3(x, y, z))
            if (p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z >= lo.z) return true
            y += 0.002f
        }
        return false
    }

    /** Skip test (x, y, z) for one rain drop (16 mm streak from y) / one snow flake (at y) while the tag box [tag] is up. */
    fun rainSkip(q: Q4, tag: Aabb): (Float, Float, Float) -> Boolean { val zn = tagZone(tag); return { x, y, z -> blocks(x, y, y + 0.016f, z, Layout.RAIN_STEP, q, zn, RAIN_MARGIN) } }
    fun snowSkip(q: Q4, tag: Aabb): (Float, Float, Float) -> Boolean { val zn = tagZone(tag); return { x, y, z -> blocks(x, y - 0.002f, y + 0.002f, z, Layout.SNOW_STEP, q, zn, SNOW_MARGIN) } }
}
