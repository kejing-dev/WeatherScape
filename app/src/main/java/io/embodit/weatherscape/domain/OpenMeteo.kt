package io.embodit.weatherscape.domain

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/** A place the user keeps (design dataModel.forecast City). Names in both languages; [id] < 0 = a built-in city. */
data class City(
    val id: Long,
    val nameZh: String,
    val nameEn: String,
    val adminZh: String = "",
    val adminEn: String = "",
    val countryZh: String = "",
    val countryEn: String = "",
    val lat: Double,
    val lon: Double,
    val tz: String = "auto",
    val lastViewedMs: Long = 0L,
) {
    fun name(zh: Boolean) = (if (zh) nameZh else nameEn).ifBlank { if (zh) nameEn else nameZh }
    fun admin(zh: Boolean) = (if (zh) adminZh else adminEn).ifBlank { if (zh) adminEn else adminZh }
    fun country(zh: Boolean) = (if (zh) countryZh else countryEn).ifBlank { if (zh) countryEn else countryZh }
    /** "浙江 · 中国" (the small line under a city row / search result). */
    fun place(zh: Boolean) = listOf(admin(zh), country(zh)).filter { it.isNotBlank() && it != name(zh) }.distinct().joinToString(" · ")
    val key: String get() = if (id != 0L) "c$id" else "%.3f_%.3f".format(lat, lon)
}

/** A parsed forecast response (Open-Meteo /v1/forecast, metric, timezone=auto, forecast_days=4). */
data class Forecast(
    val utcOffsetSec: Int,
    val times: List<String>,
    val temp: List<Float>,
    val feels: List<Float>,
    val pop: List<Int?>,
    val precip: List<Float>,
    val snow: List<Float>,
    val code: List<Int>,
    val cloud: List<Int>,
    val wind: List<Float>,
    val windDir: List<Int>,
    val isDay: List<Boolean>,
    val dailyDates: List<String>,
    val sunrise: List<String>,
    val sunset: List<String>,
    val tMax: List<Float>,
    val tMin: List<Float>,
    val currentTime: String?,
    val currentTemp: Float?,
    val currentFeels: Float?,
    val currentCode: Int?,
    val currentIsDay: Boolean?,
)

object OpenMeteo {
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    const val GEO_SEARCH = "https://geocoding-api.open-meteo.com/v1/search"
    const val GEO_GET = "https://geocoding-api.open-meteo.com/v1/get"
    const val HOURLY = "temperature_2m,apparent_temperature,precipitation_probability,precipitation,snowfall,weather_code,cloud_cover,wind_speed_10m,wind_direction_10m,is_day"

    fun forecastUrl(lat: Double, lon: Double): String =
        "$FORECAST?latitude=%.4f&longitude=%.4f&current=temperature_2m,apparent_temperature,weather_code,is_day&hourly=$HOURLY&daily=sunrise,sunset,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=4".format(java.util.Locale.US, lat, lon)

    fun searchUrl(q: String, zh: Boolean): String =
        "$GEO_SEARCH?name=${java.net.URLEncoder.encode(q.trim(), "UTF-8")}&count=$SEARCH_FETCH&language=${if (zh) "zh" else "en"}&format=json"

    /** How many geocoding hits we ask for before ranking (v0.1.1: the API orders by name match, not by size). */
    const val SEARCH_FETCH = 10

    /** A query with Chinese characters is looked up with Chinese names, whatever the UI language ("西安" in English UI). */
    fun isCjk(q: String): Boolean = q.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }

    /** Search language for [q]: Chinese characters -> zh; otherwise the UI language. */
    fun searchZh(q: String, uiZh: Boolean): Boolean = if (isCjk(q)) true else uiZh

    /** Rank of a GeoNames feature code: capitals and province seats first, airports and the like last. */
    fun featureRank(code: String): Int = when (code) {
        "PPLC" -> 0; "PPLA" -> 1; "PPLA2" -> 2; "PPLA3" -> 3; "PPLA4" -> 4; "PPL", "PPLX" -> 5; "" -> 6
        else -> if (code.startsWith("PPL")) 5 else 7
    }

    fun getUrl(id: Long, zh: Boolean): String = "$GEO_GET?id=$id&language=${if (zh) "zh" else "en"}"

    private fun JSONArray?.floats(): List<Float> = if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) 0f else optDouble(it, 0.0).toFloat() }
    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) 0 else optDouble(it, 0.0).toInt() }
    private fun JSONArray?.intsOrNull(): List<Int?> = if (this == null) emptyList() else (0 until length()).map { if (isNull(it)) null else optDouble(it, 0.0).toInt() }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }

    fun parseForecast(text: String): Forecast {
        val o = JSONObject(text)
        val h = o.getJSONObject("hourly")
        val d = o.optJSONObject("daily")
        val c = o.optJSONObject("current")
        val times = h.getJSONArray("time").strings()
        require(times.isNotEmpty()) { "no hourly data" }
        return Forecast(
            utcOffsetSec = o.optInt("utc_offset_seconds", 0),
            times = times,
            temp = h.optJSONArray("temperature_2m").floats(),
            feels = h.optJSONArray("apparent_temperature").floats(),
            pop = h.optJSONArray("precipitation_probability").intsOrNull(),
            precip = h.optJSONArray("precipitation").floats(),
            snow = h.optJSONArray("snowfall").floats(),
            code = h.optJSONArray("weather_code").ints(),
            cloud = h.optJSONArray("cloud_cover").ints(),
            wind = h.optJSONArray("wind_speed_10m").floats(),
            windDir = h.optJSONArray("wind_direction_10m").ints(),
            isDay = h.optJSONArray("is_day").ints().map { it == 1 },
            dailyDates = d?.optJSONArray("time").strings(),
            sunrise = d?.optJSONArray("sunrise").strings(),
            sunset = d?.optJSONArray("sunset").strings(),
            tMax = d?.optJSONArray("temperature_2m_max").floats(),
            tMin = d?.optJSONArray("temperature_2m_min").floats(),
            currentTime = c?.optString("time"),
            currentTemp = c?.takeIf { it.has("temperature_2m") && !it.isNull("temperature_2m") }?.getDouble("temperature_2m")?.toFloat(),
            currentFeels = c?.takeIf { it.has("apparent_temperature") && !it.isNull("apparent_temperature") }?.getDouble("apparent_temperature")?.toFloat(),
            currentCode = c?.takeIf { it.has("weather_code") && !it.isNull("weather_code") }?.getInt("weather_code"),
            currentIsDay = c?.takeIf { it.has("is_day") && !it.isNull("is_day") }?.getInt("is_day")?.let { it == 1 },
        )
    }

    /** The city's local time of [nowUtcMs] as "yyyy-MM-ddTHH:00" (Open-Meteo hourly.time has no offset). */
    fun localHourKey(nowUtcMs: Long, utcOffsetSec: Int): String {
        val t = LocalDateTime.ofEpochSecond(nowUtcMs / 1000 + utcOffsetSec, 0, ZoneOffset.UTC)
        return "%04d-%02d-%02dT%02d:00".format(t.year, t.monthValue, t.dayOfMonth, t.hour)
    }

    private fun minutesOf(iso: String): Int = runCatching { iso.substring(11, 13).toInt() * 60 + iso.substring(14, 16).toInt() }.getOrDefault(-1)

    /**
     * Cuts the 48 hours that start at the city's current local hour (design weatherModel.time: now(UTC) + utc_offset,
     * floored to the hour). `current` overwrites hours[0]'s temp / feels / code / isDay (D38, R2-3). Returns null when
     * no hour of the forecast is current or later.
     */
    fun slice(f: Forecast, nowUtcMs: Long, source: Source, ageHours: Int = 0): Slice? {
        val key = localHourKey(nowUtcMs, f.utcOffsetSec)
        if (f.times.last() < key) return null
        val start = f.times.indexOfLast { it <= key }.coerceAtLeast(0)
        val end = minOf(f.times.size, start + 48)
        if (end <= start) return null
        val day0 = LocalDate.parse(f.times[start].substring(0, 10))
        val hours = (start until end).map { i ->
            val t = f.times[i]
            val date = LocalDate.parse(t.substring(0, 10))
            Hour(
                time = t,
                dayIndex = ChronoUnit.DAYS.between(day0, date).toInt(),
                hod = t.substring(11, 13).toInt(),
                temp = f.temp.getOrElse(i) { 0f },
                feels = f.feels.getOrElse(i) { f.temp.getOrElse(i) { 0f } },
                pop = f.pop.getOrNull(i),
                precip = f.precip.getOrElse(i) { 0f },
                snow = f.snow.getOrElse(i) { 0f },
                code = f.code.getOrElse(i) { 0 },
                cloud = f.cloud.getOrElse(i) { 0 },
                wind = f.wind.getOrElse(i) { 0f },
                windDir = f.windDir.getOrElse(i) { 0 },
                isDay = f.isDay.getOrElse(i) { true },
                weekday = date.dayOfWeek.value,
            )
        }.toMutableList()
        // R2-3: the live current values become hour 0 (only when they belong to this hour)
        if (f.currentTemp != null && (f.currentTime == null || f.currentTime.take(13) == f.times[start].take(13))) {
            hours[0] = hours[0].copy(
                temp = f.currentTemp,
                feels = f.currentFeels ?: hours[0].feels,
                code = f.currentCode ?: hours[0].code,
                isDay = f.currentIsDay ?: hours[0].isDay,
            )
        }
        val days = f.dailyDates.indices.mapNotNull { i ->
            val di = ChronoUnit.DAYS.between(day0, LocalDate.parse(f.dailyDates[i])).toInt()
            if (di < 0) null else SunDay(di, minutesOf(f.sunrise.getOrElse(i) { "" }).takeIf { it >= 0 } ?: 390, minutesOf(f.sunset.getOrElse(i) { "" }).takeIf { it >= 0 } ?: 1080,
                f.tMax.getOrElse(i) { 0f }, f.tMin.getOrElse(i) { 0f })
        }
        return Slice(hours, days, source, ageHours, key = "${f.times[start]}|${f.times.size}|${f.currentTemp}")
    }

    fun currentsUrl(cs: List<City>): String =
        "$FORECAST?latitude=${cs.joinToString(",") { "%.4f".format(java.util.Locale.US, it.lat) }}&longitude=${cs.joinToString(",") { "%.4f".format(java.util.Locale.US, it.lon) }}&current=temperature_2m,weather_code&timezone=auto"

    /** One object (one city) or an array of objects (several) -> (temperature, weather code) per city, in order. */
    fun parseCurrents(text: String): List<Pair<Float, Int>> {
        val t = text.trim()
        val objs = if (t.startsWith("[")) JSONArray(t).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(t))
        return objs.map { o -> val c = o.getJSONObject("current"); c.getDouble("temperature_2m").toFloat() to c.getInt("weather_code") }
    }

    /** One geocoding result object -> a City in [zh] (the other language's name is filled later through /v1/get). */
    fun cityOf(o: JSONObject, zh: Boolean): City {
        // GeoNames mixes traditional names into language=zh (倫敦, 東京): rows read in simplified Chinese
        val name = o.optString("name").let { if (zh) Hanzi.toSimplified(it) else it }
        val admin = o.optString("admin1").let { if (zh) Hanzi.toSimplified(it) else it }
        val country = o.optString("country").let { if (zh) Hanzi.toSimplified(it) else it }
        return City(
            id = o.optLong("id"),
            nameZh = if (zh) name else "", nameEn = if (zh) "" else name,
            adminZh = if (zh) admin else "", adminEn = if (zh) "" else admin,
            countryZh = if (zh) country else "", countryEn = if (zh) "" else country,
            lat = o.optDouble("latitude"), lon = o.optDouble("longitude"), tz = o.optString("timezone", "auto"),
        )
    }

    fun parseSearch(text: String, zh: Boolean, query: String = ""): List<City> = parseSearchMany(listOf(text), zh, query)

    /**
     * Several geocoding answers for one search (the query, its traditional-character spelling, an English alias) merged
     * by id and ranked. v0.1.3 (QA #3 q3-1): the exact-name rule came first and compared the language=zh name ("巴黎")
     * with a Latin query ("Paris"), so US towns that keep "Paris" in Chinese won. Now big places (capital, province seat
     * or >= 1 M people) come first, then exact names (against every spelling), then the feature rank, population, API order.
     */
    fun parseSearchMany(texts: List<String>, zh: Boolean, query: String = ""): List<City> {
        val seen = HashSet<Long>()
        val hits = texts.flatMap { t ->
            val arr = runCatching { JSONObject(t).optJSONArray("results") }.getOrNull() ?: return@flatMap emptyList<JSONObject>()
            (0 until arr.length()).map { arr.getJSONObject(it) }
        }.filter { seen.add(it.optLong("id")) }
        val qs = spellings(query.trim())
        val ranked = hits.withIndex().sortedWith(compareBy(
            { (_, o) -> if (isMajor(o)) 0 else 1 },
            { (_, o) -> if (qs.isNotEmpty() && norm(o.optString("name")) in qs) 0 else 1 },
            { (_, o) -> featureRank(o.optString("feature_code")) },
            { (_, o) -> -o.optLong("population", 0L) },
            { (i, _) -> i },
        )).map { it.value }
        return ranked.take(3).map { cityOf(it, zh) }
    }

    /** A capital, a first-level seat, or a place of >= 1 M people. */
    fun isMajor(o: JSONObject): Boolean {
        val fc = o.optString("feature_code")
        return fc == "PPLC" || fc == "PPLA" || o.optLong("population", 0L) >= 1_000_000L
    }

    private fun norm(s: String) = Hanzi.toSimplified(s.trim().lowercase()).removeSuffix("市")
    private fun spellings(q: String): Set<String> = if (q.isEmpty()) emptySet() else
        (listOf(q) + aliasesOf(q)).map { norm(it) }.toSet()

    /** English names of a Chinese query that GeoNames does not list in simplified Chinese (东京 -> Tokyo, 首尔 -> Seoul). */
    fun aliasesOf(q: String): List<String> {
        val k = Hanzi.toSimplified(q.trim()).removeSuffix("市")
        if (k.isEmpty() || !isCjk(k)) return emptyList()
        return (DefaultCities.ALL.filter { it.nameZh == k }.map { it.nameEn } + listOfNotNull(EXTRA_ALIAS[k])).distinct()
    }

    private val EXTRA_ALIAS = mapOf(
        "洛杉矶" to "Los Angeles", "华盛顿" to "Washington", "多伦多" to "Toronto", "蒙特利尔" to "Montreal",
        "大阪" to "Osaka", "京都" to "Kyoto", "首尔" to "Seoul", "釜山" to "Busan", "墨尔本" to "Melbourne",
        "罗马" to "Rome", "马德里" to "Madrid", "巴塞罗那" to "Barcelona", "米兰" to "Milan", "维也纳" to "Vienna",
        "阿姆斯特丹" to "Amsterdam", "布鲁塞尔" to "Brussels", "苏黎世" to "Zurich", "伊斯坦布尔" to "Istanbul",
        "开罗" to "Cairo", "孟买" to "Mumbai", "新德里" to "New Delhi", "雅加达" to "Jakarta", "吉隆坡" to "Kuala Lumpur",
        "河内" to "Hanoi", "胡志明" to "Ho Chi Minh City", "马尼拉" to "Manila", "台北" to "Taipei", "高雄" to "Kaohsiung",
        "澳门" to "Macau", "西雅图" to "Seattle", "波士顿" to "Boston", "迈阿密" to "Miami", "拉斯维加斯" to "Las Vegas",
        "墨西哥城" to "Mexico City", "里约热内卢" to "Rio de Janeiro", "布宜诺斯艾利斯" to "Buenos Aires", "奥克兰" to "Auckland",
        "伦敦" to "London", "纽约" to "New York", "东京" to "Tokyo", "巴黎" to "Paris", "北京" to "Beijing",
    )

    /**
     * The requests behind one search, in order: the query in its search language, the traditional spelling (GeoNames
     * lists 東京 / 倫敦 / 紐約, not 东京 / 伦敦 / 纽约), then English aliases, all asked in the same language so the rows
     * read in Chinese. The first one is required, the rest are best effort.
     */
    fun searchQueries(q: String, uiZh: Boolean): List<String> {
        val t = q.trim()
        if (!isCjk(t)) return listOf(t)
        return (listOf(t, Hanzi.toTraditional(t)) + aliasesOf(t)).distinct()
    }

    /** Merges the other language's names from a /v1/get answer. */
    fun mergeNames(c: City, text: String, zh: Boolean): City {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return c
        val n = cityOf(o, zh)
        return if (zh) c.copy(nameZh = n.nameZh.ifBlank { c.nameZh }, adminZh = n.adminZh.ifBlank { c.adminZh }, countryZh = n.countryZh.ifBlank { c.countryZh })
        else c.copy(nameEn = n.nameEn.ifBlank { c.nameEn }, adminEn = n.adminEn.ifBlank { c.adminEn }, countryEn = n.countryEn.ifBlank { c.countryEn })
    }
}
