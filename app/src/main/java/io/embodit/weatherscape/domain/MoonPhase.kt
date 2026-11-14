package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v0.1.4 (user 11-14 "月亮…要有自己的模型"): the moon's phase, shown with a dark shell over the crater model's night side.
 * Age in days since the reference new moon 2000-01-06 18:14 UTC (synodic month 29.530589 d); the local date of the slice
 * hour is good to a few hours, far below what a 2.6 cm moon can show.
 */
object MoonPhase {
    const val SYNODIC = 29.530589
    /** Days from 1970-01-01 to the reference new moon 2000-01-06 18:14 UTC. */
    private const val REF_DAYS = 10957.0 + 5.0 + (18 * 60 + 14) / 1440.0

    /** Days since 1970-01-01 of an Open-Meteo local time "yyyy-MM-ddTHH:mm" (null when it can't be read). */
    fun epochDays(time: String): Double? = runCatching {
        val y = time.substring(0, 4).toInt(); val m = time.substring(5, 7).toInt(); val d = time.substring(8, 10).toInt()
        val hh = if (time.length >= 13) time.substring(11, 13).toInt() else 12
        // days from civil (Howard Hinnant)
        val yy = if (m <= 2) y - 1 else y
        val era = (if (yy >= 0) yy else yy - 399) / 400
        val yoe = yy - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        (era * 146097 + doe - 719468).toDouble() + hh / 24.0
    }.getOrNull()

    /** Age 0 ..< 29.53 days (0 new, ~7.4 first quarter, ~14.8 full, ~22.1 last quarter). */
    fun age(epochDays: Double): Double { val a = (epochDays - REF_DAYS) % SYNODIC; return if (a < 0) a + SYNODIC else a }

    /** Lit fraction of the disc seen from the earth, 0 new .. 1 full. */
    fun illumination(age: Double): Double = (1 - cos(2 * PI * age / SYNODIC)) / 2

    /**
     * Direction from the moon to the sun in the diorama frame for the user in front (+Z): new moon = -Z (the lit side
     * faces away), full = +Z; waxing lights the right (+X, as seen from the northern hemisphere), waning the left.
     */
    fun sunDir(age: Double): V3 { val phi = 2 * PI * age / SYNODIC; return V3(sin(phi).toFloat(), 0f, (-cos(phi)).toFloat()) }

    /** The turn that points the shade shell's pole (+Z in its mesh) away from the sun. */
    fun shadeTurn(age: Double): Q4 = Q4.fromTo(V3(0f, 0f, 1f), sunDir(age) * -1f)
}
