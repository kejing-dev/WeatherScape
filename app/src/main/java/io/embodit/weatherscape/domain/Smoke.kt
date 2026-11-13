package io.embodit.weatherscape.domain

/**
 * Chimney smoke (feels < 12°, dry): six puffs rising 7 cm from the stone house's chimney and leaning downwind.
 *
 * v0.1.1 (QA #1 q1-1): the puffs passed in front of the house tag and hid its number ("体感 9°" read "体感 9"). The smoke
 * now yields to every ask tag: while a tag is up the puffs shrink away in about 0.15 s and grow back after it goes, so
 * no tag is ever crossed by smoke. Scene and unit tests read the same functions.
 */
object Smoke {
    const val N = 6
    /** Puff sphere radius at scale 1 (Pieces.sphere(0.006)). */
    const val PUFF_R = 0.006f
    /** Fade time constant (s) of the yield, ~0.15 s to gone. */
    const val FADE_TAU = 0.05f
    /** Chimney top, house-local (Tripo chimney at model (0.05, 0.98, 0.35) x 0.0917). */
    val CHIMNEY_TOP = V3(0.005f, 0.092f, 0.032f)
    val SOURCE: V3 get() = ModelCalib.HOUSE_AT + CHIMNEY_TOP

    /** Puff [i] at time [t] (s): centre (scene) and scale, before the yield factor. */
    fun puff(i: Int, t: Float, downX: Float, downZ: Float, windLevel: Int): Pair<V3, Float> {
        val k = (t * 0.25f + i / N.toFloat()) % 1f
        val drift = k * 0.05f * (0.3f + windLevel * 0.4f)
        return (SOURCE + V3(downX * drift, k * 0.07f, downZ * drift)) to (0.6f + k * 1.4f)
    }

    /** Target of the yield factor: 1 = smoke shows, 0 = it has made way for a tag (or there is no smoke). */
    fun target(smoke: Boolean, tagUp: Boolean): Float = if (smoke && !tagUp) 1f else 0f

    fun puffBounds(c: V3, scale: Float): Aabb { val d = 2 * PUFF_R * scale; return Aabb.centred(c, V3(d, d, d)) }
}
