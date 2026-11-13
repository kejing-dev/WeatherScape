package io.embodit.weatherscape.domain

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/** What falls from the clouds. */
enum class Precip { NONE, RAIN, SNOW }

/**
 * Everything the diorama shows for one ring position (design mustHave.visibleFeedback + spatialLayout + lighting). Pure:
 * the scene animates toward it (clouds grow 0.8 s, glows fade 0.8 s), the unit tests read it directly (M3 / M5 / M16).
 */
data class SceneParams(
    val hour: Int,
    val sky: Sky,
    val code: Int,
    val clouds: Int,
    /** 0 white, 1 grey (overcast / fog), 2 dark (rain / snow), 3 storm. */
    val cloudShade: Int,
    val precip: Precip,
    /** 1, 2 or 4 sheets (design precip: <= 1 mm/h 1, <= 4 two, more four). */
    val sheets: Int,
    val tiltDeg: Float,
    /** Unit vector (x, z) the wind blows TOWARD (downwind). */
    val downX: Float,
    val downZ: Float,
    val windLevel: Int,
    val treeAmpDeg: Float,
    /** Vane yaw (radians about +Y): its +Z arrow points to where the wind comes from. */
    val vaneYaw: Float,
    val smoke: Boolean,
    val sunPos: V3,
    val sunUp: Boolean,
    val sunDim: Boolean,
    val moonPos: V3,
    val moonUp: Boolean,
    /** 1 = day, 0 = night (twilight ramps over 40 min around sunrise / sunset). */
    val day: Float,
    val isDay: Boolean,
    /** Global brightness factor on every PBR base colour (design lighting fallback: day 1.0, overcast 0.8, night 0.45). */
    val brightness: Float,
    val lightIntensity: Float,
    val lightWarm: Float,
    val lightDir: V3,
    val fog: Boolean,
    val snowDepth: Float,
    val storm: Boolean,
    val drift: Float,
)

object SceneModel {
    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Where the wind comes from as a unit (x, z): north = +Z, east = -X (design math). */
    fun fromVec(deg: Int): Pair<Float, Float> { val r = Layout.deg(deg.toFloat()); return -sin(r) to cos(r) }

    fun at(s: Slice, pos: Float, night: Boolean? = null): SceneParams {
        val h = pos.roundToInt().coerceIn(0, s.hours.size - 1)
        val x = s.at(h)
        val sky = x.sky
        // --- time of day (continuous: the hour below + the fraction)
        val i0 = floor(pos).toInt().coerceIn(0, s.hours.size - 1)
        val frac = (pos - i0).coerceIn(0f, 1f)
        val base = s.at(i0)
        val minutes = base.hod * 60f + frac * 60f
        val sd = s.day(base.dayIndex)
        val rise = (sd?.riseMin ?: 390).toFloat()
        val set = (sd?.setMin ?: 1080).toFloat()
        var dayF = smooth(rise - 20f, rise + 20f, minutes) * (1f - smooth(set - 20f, set + 20f, minutes))
        var isDay = x.isDay
        var sunK = (minutes - rise) / (set - rise)
        var sunUp = sunK in 0f..1f
        val nightLen = 1440f - (set - rise)
        var moonK = if (minutes >= set) (minutes - set) / nightLen else (minutes + 1440f - set) / nightLen
        if (night != null) {
            isDay = !night
            dayF = if (night) 0f else 1f
            sunUp = !night; sunK = 0.5f; moonK = 0.5f
        }
        val cloudy = x.cloud / 100f
        val heavySky = sky == Sky.OVERCAST || sky == Sky.RAIN || sky == Sky.STORM || sky == Sky.SNOW
        val moonUp = !sunUp && !heavySky
        // --- clouds, precipitation, wind
        var clouds = (x.cloud / 17f).roundToInt().coerceIn(0, 6)
        if (sky == Sky.FOG) clouds = minOf(clouds, 2)
        if (sky.wet || sky == Sky.OVERCAST) clouds = maxOf(clouds, 4)
        val shade = when (sky) { Sky.STORM -> 3; Sky.RAIN, Sky.SNOW -> 2; Sky.OVERCAST, Sky.FOG -> 1; else -> 0 }
        val precip = when { sky == Sky.SNOW || (x.snow > 0.05f && x.precip <= x.snow) -> Precip.SNOW; sky == Sky.RAIN || sky == Sky.STORM -> Precip.RAIN; else -> Precip.NONE }
        val amount = maxOf(x.precip, x.snow)
        val sheets = when { precip == Precip.NONE -> 0; amount <= 1f -> 1; amount <= 4f -> 2; else -> 4 }
        val wl = Wind.level(x.wind)
        val (fx, fz) = fromVec(x.windDir)
        // --- light (design lighting): warm sun by day, cool moon at night; overcast halves the key light
        val overcast = if (heavySky) 1f else (cloudy - 0.3f).coerceIn(0f, 0.7f) / 0.7f * 0.6f
        val dayK = 1f - 0.2f * overcast
        val fogK = if (sky == Sky.FOG) 0.85f else 1f
        val bright = (0.45f + (dayK - 0.45f) * dayF) * fogK
        val sunPos = Layout.onArc(sunK.coerceIn(0f, 1f))
        val moonPos = Layout.onArc(moonK.coerceIn(0f, 1f), Layout.ARC_R * 0.95f)
        val src = if (dayF > 0.5f) sunPos else V3(-0.25f, 0.25f, 0.05f)
        val dir = (V3(0f, Layout.TOP, 0f) - V3(src.x, maxOf(src.y, -0.05f) + 0.25f, src.z + 0.35f)).norm()
        val lightI = (0.25f + (0.9f * (1f - 0.5f * overcast) - 0.25f) * dayF)
        val warm = if (dayF > 0.5f) (1f - smooth(0.15f, 0.35f, sunK) * (1f - smooth(0.65f, 0.85f, sunK))) else 0f
        // --- snow cover: snowfall since hour 0 (cm), gone when it stops snowing and warms past 2°
        var acc = 0f
        for (i in 0..h) acc += s.at(i).snow
        if (sky != Sky.SNOW && x.temp > 2f) acc = 0f
        return SceneParams(
            hour = h, sky = sky, code = x.code, clouds = clouds, cloudShade = shade, precip = precip, sheets = sheets,
            tiltDeg = minOf(Layout.PRECIP_TILT_MAX_DEG, wl * 4f), downX = -fx, downZ = -fz, windLevel = wl,
            treeAmpDeg = floatArrayOf(1f, 3f, 6f, 9f)[wl], vaneYaw = -Layout.deg(x.windDir.toFloat()),
            smoke = x.feels < 12f && !x.falling,
            sunPos = sunPos, sunUp = sunUp, sunDim = cloudy > 0.7f, moonPos = moonPos, moonUp = moonUp,
            day = dayF, isDay = isDay, brightness = bright, lightIntensity = lightI, lightWarm = warm, lightDir = dir,
            fog = sky == Sky.FOG, snowDepth = (acc / 2f).coerceIn(0f, 1f), storm = sky == Sky.STORM,
            drift = 0.005f + wl * 0.008f,
        )
    }

    /** Where cloud [i] (of 6) sits at time [tSec] for a drift of [speed] m/s toward (dx, dz) (design clouds). */
    fun cloudPos(i: Int, tSec: Float, speed: Float, dx: Float, dz: Float): Pair<V3, Float> {
        val baseX = -0.16f + i * 0.064f
        val baseZ = -0.13f + ((i * 53) % 7) * 0.04f
        val y = 0.075f + ((i * 37) % 5) * 0.018f
        // travel along the drift direction, wrapping inside the cloud disc (r <= 0.22 minus the cloud half size)
        val lim = Layout.CLOUD_R - Layout.CLOUD_W / 2
        val u0 = baseX * dx + baseZ * dz
        val v0 = -baseX * dz + baseZ * dx
        val span = 2 * lim
        var u = ((u0 + speed * tSec + lim) % span + span) % span - lim
        val vMax = kotlin.math.sqrt((lim * lim - u * u).coerceAtLeast(0f))
        val v = v0.coerceIn(-vMax, vMax)
        // grow in at the upwind edge, shrink out at the downwind edge (0.8 s at 3 cm/s ~ 2.4 cm)
        val edge = smooth(-lim, -lim + 0.03f, u) * (1f - smooth(lim - 0.03f, lim, u))
        if (span <= 0f) u = 0f
        return V3(u * dx - v * dz, y, u * dz + v * dx) to edge
    }
}
