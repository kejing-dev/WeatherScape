package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v0.1.4 (user 11-14: "❄️也是" white blobs): falling snow is a field of six-armed crystal cards (a painted crystal with
 * alpha, Tex.crystal) that face the user (+Z), turn slowly in their plane and drift down while swaying. The roof / island
 * snow cover stays as it was.
 *
 * [MAX] flakes live in the precipitation cylinder (r [Layout.PRECIP_R], [Layout.PRECIP_Y0] .. [Layout.PRECIP_Y1], leaning
 * downwind like the rain); 1 / 2 / 4 "sheets" (the amount, SceneModel) show 9 / 18 / 36 of them. Each flake grows in at the
 * top and melts away at the bottom (no pop). While an ask tag is up, any flake that would pass in front of the tag is
 * hidden (the same rule as the rain, q3-2: [PrecipYield.blocks]).
 */
object Snowfall {
    const val MAX = 36
    const val PER_SHEET = 9
    /** Crystal card size range (m): 1.3 - 1.7 cm in scene units (x 1.42 in the room) so the six arms read from 1.75 m. */
    const val SIZE_MIN = 0.013f
    const val SIZE_MAX = 0.017f
    private val H = Layout.PRECIP_Y1 - Layout.PRECIP_Y0

    class Flake(val x: Float, val z: Float, val phase: Float, val speed: Float, val size: Float, val spin: Float, val turn0: Float, val swayF: Float)

    val flakes: List<Flake> = Random(2026).let { r ->
        (0 until MAX).map { i ->
            // even spread over the disc (sunflower), a little jitter
            val rr = (Layout.PRECIP_R - SIZE_MAX) * sqrt((i + 0.5f) / MAX)
            val a = i * 2.39996f + r.nextFloat() * 0.3f
            Flake(rr * cos(a), rr * sin(a), r.nextFloat(), 0.055f + r.nextFloat() * 0.03f, SIZE_MIN + r.nextFloat() * (SIZE_MAX - SIZE_MIN),
                (if (r.nextBoolean()) 1f else -1f) * (0.25f + r.nextFloat() * 0.45f), r.nextFloat() * PI.toFloat(), 0.8f + r.nextFloat() * 0.8f)
        }
    }

    /** How many flakes show for [sheets] (0, 1, 2, 4). */
    fun count(sheets: Int): Int = (sheets * PER_SHEET).coerceIn(0, MAX)

    data class Pose(val local: V3, val size: Float, val turn: Float)

    /** Flake [i] at time [t] in the cylinder's own frame (origin = (0, PRECIP_Y0, 0) before the downwind lean). */
    fun pose(i: Int, t: Float): Pose {
        val f = flakes[i]
        val y = H - ((t * f.speed + f.phase * H) % H)
        val sway = 0.005f
        val x = f.x + sway * sin(t * f.swayF + i)
        val z = f.z + sway * 0.6f * cos(t * f.swayF * 0.8f + i)
        // grow in over the top 1.2 cm, melt over the bottom 1 cm
        val k = ((H - y) / 0.012f).coerceIn(0f, 1f) * (y / 0.010f).coerceIn(0f, 1f)
        return Pose(V3(x, y, z), f.size * k, f.turn0 + f.spin * t)
    }

    /** Scene position of a flake (cylinder leaning by [tilt]). */
    fun world(p: Pose, tilt: Q4): V3 = V3(0f, Layout.PRECIP_Y0, 0f) + tilt.rotate(p.local)

    /** Room kept around a tag: the flake's half diagonal + what it moves while it shrinks away (~0.1 s). */
    const val YIELD_MARGIN = PrecipYield.SNOW_MARGIN + SIZE_MAX

    /**
     * True when a flake at scene position [p] is in front of (or inside) the tag box [tag] grown by [YIELD_MARGIN]: it
     * shrinks away while that tag is up (the tag faces +Z; flakes behind it are hidden by the opaque plate).
     */
    fun yields(p: V3, tag: Aabb): Boolean {
        val zone = PrecipYield.tagZone(tag)
        val m = YIELD_MARGIN
        return p.x in (zone.lo.x - m)..(zone.hi.x + m) && p.y in (zone.lo.y - m)..(zone.hi.y + m) && p.z >= zone.lo.z - m
    }
}
