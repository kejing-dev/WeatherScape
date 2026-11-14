package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Chimney smoke (feels < 12°, dry).
 *
 * v0.1.4 (user 11-14 on the headset: "烟都要有自己的模型…不然都是白模"): no more ball puffs. A small Tripo smoke curl sits on
 * the chimney mouth (the source) and [N] soft wisps rise from it, one after another: each wisp is a card with a painted,
 * semi-transparent smoke texture that rises 7.5 cm in [LIFE] s, leans downwind, curls a little, grows from 1.6 to 5 cm,
 * turns slowly and fades out (fade in 0.5 s, then out). Cards face the user (+Z), like the tags.
 *
 * v0.1.1 (QA #1 q1-1): smoke yields to every ask tag: while a tag is up it shrinks away in about 0.15 s ([target]).
 * Scene and unit tests read the same functions.
 */
object Smoke {
    const val N = 7
    /** Seconds a wisp lives (rise + fade). */
    const val LIFE = 4.2f
    /** Wisp card size (m, square) at birth and at the end of its life. */
    const val SIZE0 = 0.016f
    const val SIZE1 = 0.050f
    /** Peak opacity of a wisp. */
    const val ALPHA = 0.78f
    /** Fade time constant (s) of the yield, ~0.15 s to gone. */
    const val FADE_TAU = 0.05f
    /** Chimney top, house-local (Tripo chimney at model (0.05, 0.98, 0.35) x 0.0917). */
    val CHIMNEY_TOP = V3(0.005f, 0.092f, 0.032f)
    val SOURCE: V3 get() = ModelCalib.HOUSE_AT + CHIMNEY_TOP
    /** The Tripo smoke curl's height on the chimney mouth (its bottom centre sits on [SOURCE]). */
    const val CURL_H = 0.024f

    data class Wisp(val c: V3, val size: Float, val alpha: Float, val spin: Float)

    /** Wisp [i] at time [t] (s): centre (scene), card size, opacity (before the yield factor) and in-plane turn (rad). */
    fun wisp(i: Int, t: Float, downX: Float, downZ: Float, windLevel: Int): Wisp {
        val age = (t + i * LIFE / N) % LIFE
        val k = age / LIFE
        val drift = k.pow(1.3f) * 0.05f * (0.3f + windLevel * 0.4f)
        val curl = 0.006f * sin(2f * PI.toFloat() * (k * 1.2f + i * 0.37f)) * k
        val c = SOURCE + V3(downX * drift + curl * downZ, CURL_H * 0.6f + k * 0.075f, downZ * drift - curl * downX)
        val size = SIZE0 + (SIZE1 - SIZE0) * (1f - (1f - k) * (1f - k))
        val fadeIn = (age / 0.5f).coerceIn(0f, 1f)
        val alpha = ALPHA * fadeIn * (1f - k).pow(1.3f)
        val spin = (if (i % 2 == 0) 1f else -1f) * 0.35f * age + i * 1.7f
        return Wisp(c, size, alpha, spin)
    }

    /** Target of the yield factor: 1 = smoke shows, 0 = it has made way for a tag (or there is no smoke). */
    fun target(smoke: Boolean, tagUp: Boolean): Float = if (smoke && !tagUp) 1f else 0f

    /** The box a wisp card [w] (scaled by [yield]) can cover: the card turned in its plane spans its diagonal. */
    fun wispBounds(w: Wisp, yield: Float = 1f): Aabb { val d = w.size * 1.415f * yield; return Aabb.centred(w.c, V3(d, d, 0.002f)) }

    /** The curl model's box (house smoke source), for the volume / tag tests. */
    fun curlBounds(): Aabb = Aabb(SOURCE + V3(-0.012f, 0f, -0.012f), SOURCE + V3(0.012f, CURL_H, 0.012f))
}
