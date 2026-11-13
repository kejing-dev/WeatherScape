package io.embodit.weatherscape.domain

/**
 * Every data-driven sentence in both languages (design content.tone + uiStrings): time words, sky words, the readout's
 * two lines, the advice sentence, the three ask tags, the ring's day names. Pure, so the readout, the control window,
 * the tags and the unit tests all read the same function (D38).
 */
object Texts {
    private val WEEK_EN = listOf("", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    private fun partZh(hod: Int) = when {
        hod < 5 -> "凌晨"
        hod < 9 -> "早上"
        hod < 12 -> "上午"
        hod < 14 -> "中午"
        hod < 18 -> "下午"
        else -> "晚上"
    }

    private fun clockZh(hod: Int) = "${if (hod > 12) hod - 12 else hod} 点"
    private fun clockEn(hod: Int) = "${if (hod % 12 == 0) 12 else hod % 12} ${if (hod < 12) "AM" else "PM"}"

    /**
     * "现在" / "今晚 9 点" / "明早 8 点" / "后天下午 3 点"; English "Now" / "Tonight 9 PM" / "Tomorrow 8 AM" / "Sat 3 PM"
     * (sample day: "Day 3 3 PM" never happens, it has two days). [h] is the slice hour (0 = now).
     */
    fun time(s: Slice, h: Int, zh: Boolean): String {
        if (h <= 0) return if (zh) "现在" else "Now"
        val x = s.at(h)
        val hod = x.hod
        return if (zh) {
            val day = when (x.dayIndex) {
                0 -> if (hod >= 18) "今晚" else "今天${partZh(hod)}"
                1 -> if (hod in 5..11) "明早" else "明天${partZh(hod)}"
                else -> "后天${partZh(hod)}"
            }
            "$day ${clockZh(hod)}"
        } else {
            val day = when (x.dayIndex) {
                0 -> if (hod >= 18) "Tonight" else "Today"
                1 -> "Tomorrow"
                else -> if (x.weekday in 1..7) WEEK_EN[x.weekday] else "Day ${x.dayIndex + 1}"
            }
            "$day ${clockEn(hod)}"
        }
    }

    /** The time word inside a sentence: English lower-cased except weekdays ("tomorrow 8 AM", "at 3 PM today"). */
    private fun timeIn(s: Slice, h: Int, zh: Boolean): String {
        if (zh) return time(s, h, true)
        val x = s.at(h)
        val clock = clockEn(x.hod)
        return when (x.dayIndex) {
            0 -> if (x.hod >= 18) "at $clock tonight" else "at $clock today"
            1 -> "at $clock tomorrow"
            else -> if (x.weekday in 1..7) "on ${WEEK_EN[x.weekday]} at $clock" else "at $clock"
        }
    }

    fun sky(x: Hour, zh: Boolean): String = when (x.sky) {
        Sky.CLEAR -> if (zh) "晴" else "Clear"
        Sky.PARTLY -> if (zh) "少云" else "Partly cloudy"
        Sky.OVERCAST -> if (zh) "阴" else "Cloudy"
        Sky.FOG -> if (zh) "雾" else "Fog"
        Sky.RAIN -> if (x.heavy) (if (zh) "大雨" else "Heavy rain") else (if (zh) "小雨" else "Light rain")
        Sky.STORM -> if (zh) "雷雨" else "Storm"
        Sky.SNOW -> if (zh) "雪" else "Snow"
    }

    /** Sky word from a bare weather code (city rows: no precipitation amount, so rain is just 雨 / Rain). */
    fun skyOfCode(code: Int, zh: Boolean): String = when (Sky.of(code)) {
        Sky.RAIN -> if (zh) "雨" else "Rain"
        else -> sky(Hour("", 0, 0, 0f, 0f, 0, 0f, 0f, code, 0, 0f, 0, true), zh)
    }

    /** Readout line 1 / line 2 (design spatialLayout.readout). */
    fun readout(s: Slice, h: Int, zh: Boolean, u: TempUnit): Pair<String, String> {
        val x = s.at(h)
        return time(s, h, zh) to "${sky(x, zh)} · ${Units.tempText(x.temp, u)}"
    }

    /** "体感 10° · 降水 70%" (weather page, row 2). */
    fun feelsLine(x: Hour, zh: Boolean, u: TempUnit) =
        if (zh) "体感 ${Units.tempText(x.feels, u)} · 降水 ${x.popEff}%" else "Feels ${Units.tempText(x.feels, u)} · Rain ${x.popEff}%"

    /** "最高 18° 最低 9°" of the local day the hour belongs to. */
    fun hiLoLine(s: Slice, h: Int, zh: Boolean, u: TempUnit): String {
        val d = s.at(h).dayIndex
        val day = s.hours.filter { it.dayIndex == d }
        val sd = s.days.firstOrNull { it.dayIndex == d }
        val hi = sd?.tMax ?: day.maxOf { it.temp }
        val lo = sd?.tMin ?: day.minOf { it.temp }
        return if (zh) "最高 ${Units.tempText(hi, u)} 最低 ${Units.tempText(lo, u)}" else "High ${Units.tempText(hi, u)} Low ${Units.tempText(lo, u)}"
    }

    /** Ask tags (design input.tapAsk): sky "降水 70% · 2 mm", tree "东北风 3 级 · 14 km/h", house "体感 9°". */
    fun tagSky(x: Hour, zh: Boolean) = if (zh) "降水 ${x.popEff}% · ${mm(x.precip)} mm" else "${x.popEff}% rain · ${mm(x.precip)} mm"

    fun tagTree(x: Hour, zh: Boolean, u: TempUnit): String {
        val w = "${Units.wind(x.wind, u)} ${Units.windUnit(u)}"
        val from = Wind.from(x.windDir, zh)
        return if (zh) "${from}风 ${Wind.beaufort(x.wind)} 级 · $w" else "$from wind · $w"
    }

    fun tagHouse(x: Hour, zh: Boolean, u: TempUnit) = if (zh) "体感 ${Units.tempText(x.feels, u)}" else "Feels like ${Units.tempText(x.feels, u)}"

    private fun mm(v: Float): String = if (v < 0.05f) "0" else if (v < 10f) "%.1f".format(java.util.Locale.US, v).removeSuffix(".0") else "%.0f".format(java.util.Locale.US, v)

    /** Ring day names at local midnights (design spatialLayout.ring): 今天 / 明天 / 后天, Today / Tmrw / Next. */
    fun ringDay(d: Int, zh: Boolean) = when (d) {
        0 -> if (zh) "今天" else "Today"
        1 -> if (zh) "明天" else "Tmrw"
        else -> if (zh) "后天" else "Next"
    }

    /** S08 modifier next to the time label: "3 小时前的预报" / "示例天气". Null when live. */
    fun sourceNote(s: Slice, zh: Boolean): String? = when (s.source) {
        Source.LIVE -> null
        Source.SAMPLE -> if (zh) "示例天气" else "Sample weather"
        Source.CACHE -> if (zh) "${s.ageHours.coerceAtLeast(1)} 小时前的预报" else "Forecast from ${s.ageHours.coerceAtLeast(1)} h ago"
    }

    /** Which advice rule fired (unit tests name them). */
    enum class Rule { STORM, RAIN_STARTS, RAIN_STOPS, RAIN_ALL, SHOWERS, COLD, WINDY, DRY }

    /**
     * The one advice sentence (design weatherModel.advice): window W = min(12, hours left) from the ring position [h];
     * thresholds in °C / mm; the first rule that holds wins.
     */
    fun advice(s: Slice, h: Int, zh: Boolean, u: TempUnit): Pair<Rule, String> {
        val p = h.coerceIn(0, s.endHour)
        val w = minOf(12, s.hours.size - p).coerceAtLeast(1)
        val win = (p until p + w).map { it to s.at(it) }
        val now = s.at(p)
        win.firstOrNull { it.second.sky == Sky.STORM }?.let { (i, _) ->
            val t = timeIn(s, i, zh)
            return Rule.STORM to if (zh) "${if (i == p && p == 0) "现在" else t}有雷，早点回家。" else "Storms ${if (i == 0) "now" else t}. Head home early."
        }
        if (!now.falling) {
            win.firstOrNull { it.second.popEff >= 50 }?.let { (i, _) ->
                val t = timeIn(s, i, zh)
                return Rule.RAIN_STARTS to if (zh) "${t}开始下，出门带伞。" else "Rain starts ${if (i == 0) "now" else t}. Grab an umbrella."
            }
        } else {
            val dry = win.firstOrNull { !it.second.falling }
            return if (dry != null) {
                val t = timeIn(s, dry.first, zh)
                Rule.RAIN_STOPS to if (zh) "${t}停，等等再走。" else "It stops ${t}. Wait a bit."
            } else {
                Rule.RAIN_ALL to if (zh) "这 $w 小时一直有雨，伞别离手。" else "Rain for the next $w hours. Keep the umbrella close."
            }
        }
        val maxPop = win.maxOf { it.second.popEff }
        if (maxPop in 30..49 || win.sumOf { it.second.precip.toDouble() } > 0.0) {
            return Rule.SHOWERS to if (zh) "可能有阵雨，包里塞把伞。" else "Might get showers. Pack an umbrella."
        }
        val cold = win.minBy { it.second.feels }
        if (cold.second.feels <= 5f) {
            val t = timeIn(s, cold.first, zh)
            val temp = Units.tempText(cold.second.feels, u)
            return Rule.COLD to if (zh) "$t $temp，手套带上。" else "${if (cold.first == 0) "$temp now" else "$temp ${t}"}. Gloves on."
        }
        if (win.any { Wind.level(it.second.wind) >= 2 }) {
            return Rule.WINDY to if (zh) "风有点大，晾的衣服收一收。" else "Windy out. Bring the laundry in."
        }
        return Rule.DRY to if (zh) "接下来 $w 小时都干的，伞放家里吧。" else "Dry for the next $w hours. Leave the umbrella."
    }
}
