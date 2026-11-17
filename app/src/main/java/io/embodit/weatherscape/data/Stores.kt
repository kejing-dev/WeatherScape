package io.embodit.weatherscape.data

import android.content.Context
import android.util.Log
import io.embodit.weatherscape.domain.City
import io.embodit.weatherscape.domain.GeoIp
import io.embodit.weatherscape.domain.Reply
import io.embodit.weatherscape.domain.Language
import io.embodit.weatherscape.domain.OpenMeteo
import io.embodit.weatherscape.domain.Settings
import io.embodit.weatherscape.domain.TempUnit
import io.embodit.weatherscape.domain.WeatherSource
import io.embodit.weatherscape.domain.WeatherStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "WeatherScape"

/** Atomic text file: temp file, read-back check, rename; the previous good copy stays as .bak (SaveCodec pattern). */
internal fun atomicWrite(dir: File, name: String, text: String) {
    val file = File(dir, name)
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    check(tmp.readText() == text) { "read-back mismatch" }
    if (file.exists()) file.copyTo(File(dir, "$name.bak"), overwrite = true)
    if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
}

/** files/cache-<cityKey>.json (forecast + fetch time) and files/cities.json (design dataModel.storage). */
class FileWeatherStore(context: Context) : WeatherStore {
    private val dir = context.filesDir

    override fun loadForecast(key: String): Pair<String, Long>? = listOf("cache-$key.json", "cache-$key.json.bak").firstNotNullOfOrNull { n ->
        runCatching {
            File(dir, n).takeIf { it.exists() }?.let { f -> val o = JSONObject(f.readText()); o.getString("json") to o.getLong("at") }
        }.getOrNull()
    }

    override fun saveForecast(key: String, json: String, fetchedAtMs: Long) {
        runCatching { atomicWrite(dir, "cache-$key.json", JSONObject().put("at", fetchedAtMs).put("json", json).toString()) }
            .onFailure { Log.e(TAG, "CACHE save failed", it) }
    }

    override fun loadCities(): Pair<List<City>, Long?> = runCatching {
        val f = File(dir, "cities.json").takeIf { it.exists() } ?: File(dir, "cities.json.bak").takeIf { it.exists() } ?: return emptyList<City>() to null
        decodeCities(f.readText())
    }.getOrDefault(emptyList<City>() to null)

    override fun saveCities(cities: List<City>, currentId: Long?) {
        runCatching { atomicWrite(dir, "cities.json", encodeCities(cities, currentId)) }.onFailure { Log.e(TAG, "CITIES save failed", it) }
    }

    companion object {
        fun encodeCities(cities: List<City>, currentId: Long?): String {
            val arr = JSONArray()
            for (c in cities) arr.put(JSONObject().put("id", c.id).put("nameZh", c.nameZh).put("nameEn", c.nameEn).put("adminZh", c.adminZh).put("adminEn", c.adminEn)
                .put("countryZh", c.countryZh).put("countryEn", c.countryEn).put("lat", c.lat).put("lon", c.lon).put("tz", c.tz).put("viewed", c.lastViewedMs))
            return JSONObject().put("current", currentId ?: JSONObject.NULL).put("cities", arr).toString()
        }

        fun decodeCities(text: String): Pair<List<City>, Long?> {
            val o = JSONObject(text)
            val arr = o.getJSONArray("cities")
            val list = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                City(c.getLong("id"), c.optString("nameZh"), c.optString("nameEn"), c.optString("adminZh"), c.optString("adminEn"), c.optString("countryZh"), c.optString("countryEn"),
                    c.getDouble("lat"), c.getDouble("lon"), c.optString("tz", "auto"), c.optLong("viewed"))
            }
            return list to (if (o.isNull("current")) null else o.optLong("current"))
        }
    }
}

/** Open-Meteo over HttpURLConnection (no key, design weatherModel.source); every call has an 8 s connect / read timeout. */
class HttpWeatherSource : WeatherSource {
    /** Body + the server's Date header (UTC ms, 0 when absent). */
    private fun fetch(url: String): Pair<String, Long> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", "WeatherScape/0.1 (PICO OS)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            if (code !in 200..299) error("HTTP $code")
            val date = c.getHeaderFieldDate("Date", 0L)
            return c.inputStream.bufferedReader().use { it.readText() } to date
        } finally { c.disconnect() }
    }

    private fun get(url: String): String = fetch(url).first

    override suspend fun forecast(c: City): String = get(OpenMeteo.forecastUrl(c.lat, c.lon))
    override suspend fun forecastReply(c: City): Reply = fetch(OpenMeteo.forecastUrl(c.lat, c.lon)).let { (body, date) -> Reply(body, date.takeIf { it > 0L }) }
    override suspend fun ipLocate(p: GeoIp.Provider): String = get(p.url)
    override suspend fun reverse(lat: Double, lon: Double): String = get(GeoIp.reverseUrl(lat, lon))
    override suspend fun search(q: String, zh: Boolean): String = get(OpenMeteo.searchUrl(q, zh))
    override suspend fun names(id: Long, zh: Boolean): String = get(OpenMeteo.getUrl(id, zh))
    override suspend fun currents(cs: List<City>): String = get(OpenMeteo.currentsUrl(cs))
}

/** Settings in SharedPreferences as a StateFlow (both windows and the volume recompose on change). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putString("unit", s.unit?.name ?: "")
            .putInt("sound", s.sound)
            .putInt("lastSound", s.lastSound)
            .putBoolean("seenHowTo", s.seenHowTo)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            unit = prefs.getString("unit", "")?.takeIf { it.isNotBlank() }?.let { TempUnit.valueOf(it) },
            sound = prefs.getInt("sound", 2).coerceIn(0, 3),
            lastSound = prefs.getInt("lastSound", 2).coerceIn(1, 3),
            seenHowTo = prefs.getBoolean("seenHowTo", false),
        )
    }.getOrElse { Settings() }
}
