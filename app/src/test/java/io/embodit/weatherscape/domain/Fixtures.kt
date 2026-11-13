package io.embodit.weatherscape.domain

/** Hand-made slices for the advice / scene tests (hour 0 = now). */
object Fx {
    fun hour(i: Int, code: Int = 0, pop: Int? = 0, precip: Float = 0f, temp: Float = 15f, feels: Float = temp, wind: Float = 8f, snow: Float = 0f, cloud: Int = 10) =
        Hour("2026-11-12T%02d:00".format(i % 24), i / 24, i % 24, temp, feels, pop, precip, snow, code, cloud, wind, 90, (i % 24) in 7..17, 4)

    fun slice(n: Int = 48, f: (Int) -> Hour = { hour(it) }) = Slice((0 until n).map(f), listOf(SunDay(0, 400, 1050, 18f, 8f), SunDay(1, 400, 1050, 18f, 8f), SunDay(2, 400, 1050, 18f, 8f)), Source.LIVE, key = "fx")

    /** A minimal Open-Meteo forecast JSON: [hours] hours from [startIso] (local), utc offset [offset] s, current at hour 0. */
    fun forecastJson(startIso: String, hours: Int, offset: Int, currentCode: Int = 3, currentTemp: Double = 21.5): String {
        val t0 = java.time.LocalDateTime.parse(startIso)
        val times = (0 until hours).map { "\"" + t0.plusHours(it.toLong()).toString().take(16) + "\"" }
        fun arr(v: (Int) -> String) = (0 until hours).joinToString(",", "[", "]") { v(it) }
        val dates = (0 until 4).map { "\"" + t0.toLocalDate().plusDays(it.toLong()) + "\"" }
        return """{"latitude":40.7,"longitude":-74.0,"utc_offset_seconds":$offset,"timezone":"America/New_York",
            "current":{"time":"${t0.toString().take(16)}","temperature_2m":$currentTemp,"apparent_temperature":20.0,"weather_code":$currentCode,"is_day":1},
            "hourly":{"time":[${times.joinToString(",")}],"temperature_2m":${arr { "${10 + it % 5}" }},"apparent_temperature":${arr { "${9 + it % 5}" }},
            "precipitation_probability":${arr { if (it == 5) "null" else "${it % 100}" }},"precipitation":${arr { "0.0" }},"snowfall":${arr { "0.0" }},
            "weather_code":${arr { "0" }},"cloud_cover":${arr { "20" }},"wind_speed_10m":${arr { "10.0" }},"wind_direction_10m":${arr { "45" }},"is_day":${arr { "1" }}},
            "daily":{"time":[${dates.joinToString(",")}],"sunrise":[${dates.joinToString(",") { it.dropLast(1) + "T06:40\"" }}],"sunset":[${dates.joinToString(",") { it.dropLast(1) + "T16:50\"" }}],
            "temperature_2m_max":[14,15,16,17],"temperature_2m_min":[6,7,8,9]}}"""
    }
}
