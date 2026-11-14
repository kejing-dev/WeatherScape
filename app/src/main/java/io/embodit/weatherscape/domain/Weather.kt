package io.embodit.weatherscape.domain

import kotlin.math.roundToInt

/** The seven skies of the diorama (design weatherModel.wmoMapping). */
enum class Sky { CLEAR, PARTLY, OVERCAST, FOG, RAIN, STORM, SNOW;

    val wet: Boolean get() = this == RAIN || this == STORM || this == SNOW

    companion object {
        /** WMO weather_code -> sky: 0 clear; 1-2 partly; 3 overcast; 45/48 fog; 51-67, 80-82 rain; 71-77, 85-86 snow; 95-99 storm. */
        fun of(code: Int): Sky = when {
            code >= 95 -> STORM
            code in 71..77 || code == 85 || code == 86 -> SNOW
            code in 51..67 || code in 80..82 -> RAIN
            code == 45 || code == 48 -> FOG
            code == 3 -> OVERCAST
            code == 1 || code == 2 -> PARTLY
            else -> CLEAR
        }
    }
}

/**
 * One forecast hour (metric, as Open-Meteo sends it). [dayIndex] counts local days from the slice's first hour
 * (0 = today, 1 = tomorrow, 2 = the day after); [hod] is the city's local hour of day.
 */
data class Hour(
    val time: String,
    val dayIndex: Int,
    val hod: Int,
    val temp: Float,
    val feels: Float,
    val pop: Int?,
    val precip: Float,
    val snow: Float,
    val code: Int,
    val cloud: Int,
    val wind: Float,
    val windDir: Int,
    val isDay: Boolean,
    /** Local weekday 1 = Monday .. 7 = Sunday (0 = unknown, the sample day). */
    val weekday: Int = 0,
) {
    val sky: Sky get() = Sky.of(code)
    /** Rain chance with the design's null rule: no probability -> 60 % when more than 0.1 mm falls, else 0. */
    val popEff: Int get() = pop ?: if (precip > 0.1f) 60 else 0
    /** Something is falling this hour (rain, storm or snow by code, or more than 0.1 mm). */
    val falling: Boolean get() = precip > 0.1f || sky.wet
    val heavy: Boolean get() = precip > 4f
}

/** Sunrise / sunset of one local day, minutes after local midnight. */
data class SunDay(val dayIndex: Int, val riseMin: Int, val setMin: Int, val tMax: Float, val tMin: Float)

/** Where a slice came from (S08 modifier). */
enum class Source { LIVE, CACHE, SAMPLE }

/**
 * The 48 hours the ring can show, starting at the city's current local hour (design weatherModel.time). Every reader
 * (readout, scene, 48 h strip, tags, advice) reads only [hours] (D38): the live `current` values are already folded
 * into hours[0].
 */
data class Slice(
    val hours: List<Hour>,
    val days: List<SunDay>,
    val source: Source,
    /** Hours since the cached forecast was fetched (CACHE only). */
    val ageHours: Int = 0,
    /** A key that changes when the hours change (ring face / texture refresh). */
    val key: String = "",
) {
    /** Last hour the ring may reach: min(47, hours left - 1). */
    val endHour: Int get() = (hours.size - 1).coerceIn(0, 47)
    fun at(h: Int): Hour = hours[h.coerceIn(0, hours.size - 1)]
    fun day(d: Int): SunDay? = days.firstOrNull { it.dayIndex == d } ?: days.lastOrNull()
}

/** Temperature and wind units (design i18n.units: wind follows the temperature unit). */
enum class TempUnit { C, F }

object Units {
    fun temp(c: Float, u: TempUnit): Int = if (u == TempUnit.F) (c * 9f / 5f + 32f).roundToInt() else c.roundToInt()
    /** v0.1.3 (QA #3 K2): below zero with a real minus sign U+2212 ("−1°"), an ASCII "-" read like "--1°" from afar. */
    fun tempText(c: Float, u: TempUnit): String = temp(c, u).let { if (it < 0) "\u2212${-it}°" else "$it°" }
    fun wind(kmh: Float, u: TempUnit): Int = if (u == TempUnit.F) (kmh * 0.621371f).roundToInt() else kmh.roundToInt()
    fun windUnit(u: TempUnit): String = if (u == TempUnit.F) "mph" else "km/h"
}

/** Wind: the four scene levels (design M3) and the Beaufort force for the tree tag. */
object Wind {
    /** km/h -> 0 (calm, 0-1), 1 (2-3), 2 (4-5), 3 (6 and up). */
    fun level(kmh: Float): Int = when {
        kmh < 6f -> 0
        kmh < 20f -> 1
        kmh < 39f -> 2
        else -> 3
    }

    private val BEAUFORT = floatArrayOf(1f, 6f, 12f, 20f, 29f, 39f, 50f, 62f, 75f, 89f, 103f, 118f)
    fun beaufort(kmh: Float): Int = BEAUFORT.indexOfFirst { kmh < it }.let { if (it < 0) 12 else it }

    private val ZH = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
    private val EN = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Compass word of where the wind comes FROM (meteorological direction, degrees). */
    fun from(deg: Int, zh: Boolean): String {
        val i = (((deg % 360 + 360) % 360 + 22.5f) / 45f).toInt() % 8
        return if (zh) ZH[i] else EN[i]
    }
}
