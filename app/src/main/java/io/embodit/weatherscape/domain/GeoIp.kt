package io.embodit.weatherscape.domain

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.1.6 (profile D56, user 2026-11-16 on the headset: 「我在 san jose 他怎么显示就4 摄氏度，headset 系统时间也不对，别用系统
 * 时间，用你call 的气象站的」): the first city comes from IP geolocation, never from the device time zone (the headset said
 * America/New_York while the user sat in San Jose). Keyless HTTPS services, 8 s each, tried in order; all failing -> the
 * user picks a city.
 */
object GeoIp {
    enum class Provider(val url: String) {
        IPAPI("https://ipapi.co/json/"),
        IPWHO("https://ipwho.is/"),
        IPINFO("https://ipinfo.io/json"),
    }

    val PROVIDERS = Provider.entries

    /** Where the IP says we are. */
    data class Place(val lat: Double, val lon: Double, val city: String, val region: String, val country: String, val tz: String, val via: Provider, val org: String = "")

    /** One provider's answer -> a place, or null (error / rate limit / no coordinates). */
    fun parse(p: Provider, text: String): Place? = runCatching {
        val o = JSONObject(text)
        when (p) {
            Provider.IPAPI -> {
                if (o.optBoolean("error", false)) return null
                Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city"), o.optString("region"), o.optString("country_name"), o.optString("timezone"), p, o.optString("org"))
            }
            Provider.IPWHO -> {
                if (!o.optBoolean("success", false)) return null
                Place(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city"), o.optString("region"), o.optString("country"),
                    o.optJSONObject("timezone")?.optString("id") ?: "", p,
                    o.optJSONObject("connection")?.let { c -> listOf(c.optString("org"), c.optString("isp")).filter { it.isNotBlank() }.joinToString(" / ") } ?: "")
            }
            Provider.IPINFO -> {
                val loc = o.getString("loc").split(",")
                Place(loc[0].trim().toDouble(), loc[1].trim().toDouble(), o.optString("city"), o.optString("region"), o.optString("country"), o.optString("timezone"), p, o.optString("org"))
            }
        }
    }.getOrNull()?.takeIf { it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && !(it.lat == 0.0 && it.lon == 0.0) }

    /**
     * D56 amended (controller 11-16: this network's public IP is Amazon's, the emulator located to Boardman, Oregon while
     * the user sits in San Jose): an IP owned by a cloud / data-centre / VPN provider says nothing about where the user is.
     */
    private val DATACENTER = listOf(
        "amazon", "aws", "google cloud", "microsoft", "azure", "cloudflare", "digitalocean", "digital ocean", "akamai", "linode",
        "ovh", "hetzner", "oracle", "hosting", "vpn", "data center", "datacenter", "data centre",
    )

    fun isDatacenter(org: String): Boolean { val o = org.lowercase(); return DATACENTER.any { it in o } }

    /** Great-circle distance in km. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * The geocoding hit (Open-Meteo /v1/search for the IP city name, English) nearest to the IP coordinates, if within
     * [maxKm] -> a real GeoNames city (id, both-language names later through /v1/get). Null when nothing is close.
     */
    fun nearest(searchText: String, p: Place, maxKm: Double = 60.0): City? = runCatching {
        val arr = JSONObject(searchText).optJSONArray("results") ?: return null
        (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { it to km(p.lat, p.lon, it.optDouble("latitude"), it.optDouble("longitude")) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }?.first?.let { OpenMeteo.cityOf(it, zh = false) }
    }.getOrNull()

    /** No geocoding match: a city made from the IP answer itself (same name in both languages, a stable negative id). */
    fun cityOf(p: Place): City {
        val name = p.city.ifBlank { p.region.ifBlank { "%.2f, %.2f".format(java.util.Locale.US, p.lat, p.lon) } }
        val id = -(IP_ID_BASE + (Math.round(p.lat * 100) * 100_000 + Math.round(p.lon * 100)).let { if (it < 0) -it else it })
        return City(id, name, name, p.region, p.region, p.country, p.country, p.lat, p.lon, p.tz.ifBlank { "auto" })
    }

    /** A city for system-location coordinates that matched no named place. */
    fun cityAt(lat: Double, lon: Double, name: String = ""): City =
        cityOf(Place(lat, lon, name, "", "", "", Provider.IPAPI))

    /** The nearest built-in city within [maxKm], if any. */
    fun nearestBuiltIn(lat: Double, lon: Double, maxKm: Double = 60.0): City? =
        DefaultCities.ALL.map { it to km(lat, lon, it.lat, it.lon) }.filter { it.second <= maxKm }.minByOrNull { it.second }?.first

    /** BigDataCloud keyless reverse geocoding (client-side endpoint) -> the place name around (lat, lon). */
    fun reverseUrl(lat: Double, lon: Double) =
        "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=%.4f&longitude=%.4f&localityLanguage=en".format(java.util.Locale.US, lat, lon)

    fun parseReverse(text: String): String? = runCatching {
        val o = JSONObject(text)
        listOf("city", "locality", "principalSubdivision").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
    }.getOrNull()

    /** Built-in cities use -1..-24; IP-made ones live far below. */
    const val IP_ID_BASE = 1_000_000_000L
}

/** A system location fix (LocationManager), swapped for a fake in unit tests. */
data class Fix(val lat: Double, val lon: Double, val provider: String, val ageMs: Long)

/** System location (D56 amended: first choice; ACCESS_COARSE_LOCATION asked at start). */
interface DeviceLocator {
    fun granted(): Boolean
    /** Last known (fresh enough) or one new fix; null when none. The caller bounds it with an 8 s timeout. */
    suspend fun fix(): Fix?

    companion object {
        val NONE = object : DeviceLocator { override fun granted() = false; override suspend fun fix(): Fix? = null }
    }
}

/**
 * v0.1.6 (D56): "now" comes from the weather server, not the device clock. The HTTP Date header of the forecast answer
 * gives the true UTC; without it, `current.time` (the city's local time, start of a 15-minute step) minus
 * `utc_offset_seconds` does, to within 15 minutes. Returns the skew (server - device, ms) to add to the device clock,
 * or null when the answer carries neither.
 */
object ServerClock {
    /** current.time is the start of a 15-minute step: the true time lies up to 15 minutes later. */
    private const val STEP_MS = 15 * 60_000L

    fun skew(deviceMs: Long, dateHeaderMs: Long?, currentTime: String?, utcOffsetSec: Int): Long? {
        if (dateHeaderMs != null && dateHeaderMs > 0) return dateHeaderMs - deviceMs
        val utc = currentUtcMs(currentTime, utcOffsetSec) ?: return null
        // inside the step: keep the device clock (finer); outside it: the middle of the step
        return if (deviceMs in utc..(utc + STEP_MS)) 0L else utc + STEP_MS / 2 - deviceMs
    }

    /** current.time ("2026-11-16T23:15", city local) -> UTC ms. */
    fun currentUtcMs(currentTime: String?, utcOffsetSec: Int): Long? = runCatching {
        LocalDateTime.parse(currentTime!!.take(16)).toEpochSecond(ZoneOffset.UTC) * 1000L - utcOffsetSec * 1000L
    }.getOrNull()

    /** "23:15" from current.time (the observation the panel names), or null. */
    fun observedHm(currentTime: String?): String? = currentTime?.takeIf { it.length >= 16 && it[10] == 'T' }?.substring(11, 16)

    /** The city's local wall time "HH:mm" at [utcMs]. */
    fun localHm(utcMs: Long, utcOffsetSec: Int): String {
        val t = LocalDateTime.ofEpochSecond(Math.floorDiv(utcMs, 1000L) + utcOffsetSec, 0, ZoneOffset.UTC)
        return "%02d:%02d".format(t.hour, t.minute)
    }
}
