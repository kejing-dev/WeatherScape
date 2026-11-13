package io.embodit.weatherscape.domain

import kotlin.math.PI
import kotlin.math.sin

/**
 * The built-in sample day (design stateModel.sampleDayScript): a fixed virtual clock starting on day 1 at 00:00, every
 * sky in 48 hours. 0-5 clear night (moon) -> 6-9 partly, sunrise -> 10-13 overcast -> 14-17 rain (15 light, 16 heavy)
 * -> 18-20 storm (18 dark) -> 21-26 fog night -> 27-31 snow (snow cover full) -> 32-37 clear day, wind 4-5 -> 38-43
 * partly, evening -> 44-47 clear night, wind 6+. Sunrise 7:10, sunset 17:40.
 */
object Sample {
    const val RISE_MIN = 7 * 60 + 10
    const val SET_MIN = 17 * 60 + 40

    /** The 48 sample hours; [start] moves the virtual "now" (debug `now <h>`: the script wraps after hour 47). */
    fun hours(start: Int = 0): List<Hour> = (0 until 48).map { j ->
        val i = (start + j) % 48
        val hod = (start + j) % 24
        val day = (start % 24 + j) / 24
        val isDay = hod in 7..17
        var code = 0; var cloud = 5; var pop = 0; var pr = 0f; var sn = 0f; var wind = 8f; var dir = 60
        var t = 8f + 6f * sin((hod - 9) / 24f * 2f * PI.toFloat())
        when {
            i <= 5 -> { code = 0; cloud = 5 }
            i <= 9 -> { code = 2; cloud = 35 }
            i <= 13 -> { code = 3; cloud = 95; pop = 20 }
            i <= 17 -> { code = if (i == 16) 63 else 61; cloud = 100; pop = 85; pr = if (i == 16) 6f else 1.2f; wind = 16f }
            i <= 20 -> { code = 95; cloud = 100; pop = 90; pr = 5f; wind = 28f }
            i <= 26 -> { code = 45; cloud = 60; pop = 10; wind = 3f }
            i <= 31 -> { code = 73; cloud = 100; pop = 80; sn = 0.6f; t = -2f + (i - 27) * 0.2f; wind = 14f }
            i <= 37 -> { code = 0; cloud = 10; wind = 30f; dir = 300; t = 3f + (i - 32) }
            i <= 43 -> { code = 2; cloud = 40; wind = 18f; dir = 290; t = 7f - (i - 38) * 0.5f }
            else -> { code = 0; cloud = 5; wind = 45f; dir = 280; t = 2f }
        }
        val tt = Math.round(t * 10f) / 10f
        Hour("D${(start + j) / 24 + 1} %02d:00".format(hod), day, hod, tt, Math.round((tt - 2f) * 10f) / 10f, pop, pr, sn, code, cloud, wind, dir, isDay)
    }

    fun slice(start: Int = 0): Slice = Slice(hours(start), listOf(SunDay(0, RISE_MIN, SET_MIN, 14f, 2f), SunDay(1, RISE_MIN, SET_MIN, 9f, -2f), SunDay(2, RISE_MIN, SET_MIN, 9f, -2f)), Source.SAMPLE, key = "sample$start")
}

/**
 * Built-in time zone -> city table (design weatherModel.defaultCity, content.cities: 24 cities). First launch picks the
 * city of the system time zone; a zone not in the table starts on the sample day.
 */
object DefaultCities {
    private fun c(i: Long, zh: String, en: String, cz: String, ce: String, lat: Double, lon: Double, tz: String) =
        City(-i, zh, en, countryZh = cz, countryEn = ce, lat = lat, lon = lon, tz = tz)

    val ALL = listOf(
        c(1, "北京", "Beijing", "中国", "China", 39.9075, 116.3972, "Asia/Shanghai"),
        c(2, "上海", "Shanghai", "中国", "China", 31.2222, 121.4581, "Asia/Shanghai"),
        c(3, "广州", "Guangzhou", "中国", "China", 23.1167, 113.25, "Asia/Shanghai"),
        c(4, "深圳", "Shenzhen", "中国", "China", 22.5455, 114.0683, "Asia/Shanghai"),
        c(5, "成都", "Chengdu", "中国", "China", 30.6667, 104.0667, "Asia/Chongqing"),
        c(6, "杭州", "Hangzhou", "中国", "China", 30.2936, 120.1614, "Asia/Shanghai"),
        c(7, "香港", "Hong Kong", "中国", "China", 22.2783, 114.1747, "Asia/Hong_Kong"),
        c(8, "台北", "Taipei", "中国", "China", 25.0478, 121.5319, "Asia/Taipei"),
        c(9, "东京", "Tokyo", "日本", "Japan", 35.6895, 139.6917, "Asia/Tokyo"),
        c(10, "首尔", "Seoul", "韩国", "South Korea", 37.566, 126.9784, "Asia/Seoul"),
        c(11, "新加坡", "Singapore", "新加坡", "Singapore", 1.2897, 103.8501, "Asia/Singapore"),
        c(12, "曼谷", "Bangkok", "泰国", "Thailand", 13.7539, 100.5014, "Asia/Bangkok"),
        c(13, "悉尼", "Sydney", "澳大利亚", "Australia", -33.8679, 151.2073, "Australia/Sydney"),
        c(14, "伦敦", "London", "英国", "United Kingdom", 51.5085, -0.1257, "Europe/London"),
        c(15, "巴黎", "Paris", "法国", "France", 48.8534, 2.3488, "Europe/Paris"),
        c(16, "柏林", "Berlin", "德国", "Germany", 52.5244, 13.4105, "Europe/Berlin"),
        c(17, "莫斯科", "Moscow", "俄罗斯", "Russia", 55.7522, 37.6156, "Europe/Moscow"),
        c(18, "迪拜", "Dubai", "阿联酋", "United Arab Emirates", 25.0772, 55.3093, "Asia/Dubai"),
        c(19, "纽约", "New York", "美国", "United States", 40.7143, -74.006, "America/New_York"),
        c(20, "芝加哥", "Chicago", "美国", "United States", 41.85, -87.65, "America/Chicago"),
        c(21, "丹佛", "Denver", "美国", "United States", 39.7392, -104.9847, "America/Denver"),
        c(22, "旧金山", "San Francisco", "美国", "United States", 37.7749, -122.4194, "America/Los_Angeles"),
        c(23, "温哥华", "Vancouver", "加拿大", "Canada", 49.2497, -123.1193, "America/Vancouver"),
        c(24, "圣保罗", "São Paulo", "巴西", "Brazil", -23.5475, -46.6361, "America/Sao_Paulo"),
    )

    /** Extra zones that share a table city. */
    private val ALIAS = mapOf(
        "Asia/Shanghai" to 2L, "Asia/Chongqing" to 5L, "Asia/Harbin" to 1L, "Asia/Urumqi" to 1L, "Asia/Macau" to 7L, "PRC" to 2L,
        "America/Los_Angeles" to 22L, "US/Pacific" to 22L, "US/Eastern" to 19L, "US/Central" to 20L, "US/Mountain" to 21L,
    )

    /** The city of [tz], or null (-> sample day + "tap the city above"). Shanghai wins Asia/Shanghai. */
    fun forZone(tz: String): City? {
        ALIAS[tz]?.let { id -> return ALL.first { it.id == -id } }
        return ALL.firstOrNull { it.tz == tz }
    }
}
