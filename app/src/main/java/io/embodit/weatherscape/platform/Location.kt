package io.embodit.weatherscape.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import android.util.Log
import io.embodit.weatherscape.domain.DeviceLocator
import io.embodit.weatherscape.domain.Fix
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * D56 amended (controller 11-16): system location first. ACCESS_COARSE_LOCATION (asked by the window with
 * ActivityResultContracts.RequestPermission); a last known fix younger than 30 min, else one fresh fix from the best
 * enabled provider (fused > network > gps > passive). The flow bounds the whole call by 8 s.
 */
class AndroidLocator(context: Context) : DeviceLocator {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)
    private val exec = Executors.newSingleThreadExecutor()

    override fun granted(): Boolean =
        app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> {
        val all = runCatching { lm?.allProviders ?: emptyList() }.getOrDefault(emptyList())
        return listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { it in all && runCatching { lm!!.isProviderEnabled(it) }.getOrDefault(it == LocationManager.PASSIVE_PROVIDER) }
    }

    private fun ageMs(l: Location) = (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L

    @Suppress("MissingPermission")
    override suspend fun fix(): Fix? {
        if (!granted() || lm == null) return null
        val ps = providers()
        Log.i(TAG, "LOCATION providers=$ps all=${runCatching { lm.allProviders }.getOrNull()}")
        val last = ps.mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }.minByOrNull { ageMs(it) }
        if (last != null && ageMs(last) < 30 * 60_000L) return Fix(last.latitude, last.longitude, last.provider ?: "?", ageMs(last))
        val p = ps.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return last?.let { Fix(it.latitude, it.longitude, it.provider ?: "?", ageMs(it)) }
        val fresh = suspendCancellableCoroutine<Location?> { cont ->
            val cancel = android.os.CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            runCatching { lm.getCurrentLocation(p, cancel, exec) { l -> if (cont.isActive) cont.resume(l) } }
                .onFailure { if (cont.isActive) cont.resume(null) }
        }
        val l = fresh ?: last
        return l?.let { Fix(it.latitude, it.longitude, it.provider ?: p, ageMs(it)) }
    }
}
