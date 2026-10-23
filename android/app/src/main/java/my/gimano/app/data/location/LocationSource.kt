package my.gimano.app.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import my.gimano.app.core.geo.GeoPoint
import my.gimano.app.core.nav.RouteGeometry

/** A position fix, independent of android.location for testability. */
data class Fix(
    val point: GeoPoint,
    val speedMps: Float? = null,
    val bearing: Float? = null,
    val accuracyM: Float? = null,
    val timeMs: Long = System.currentTimeMillis(),
    val simulated: Boolean = false,
)

fun Location.toFix() = Fix(
    GeoPoint(latitude, longitude),
    if (hasSpeed()) speed else null,
    if (hasBearing()) bearing else null,
    if (hasAccuracy()) accuracy else null,
    time,
)

class FusedLocationSource(private val context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun lastKnown(): Fix? = if (!hasPermission()) null else runCatching { client.lastLocation.await()?.toFix() }.getOrNull()

    @SuppressLint("MissingPermission")
    fun updates(intervalMs: Long = 1_000): Flow<Fix> = callbackFlow {
        if (!hasPermission()) {
            close(); return@callbackFlow
        }
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(r: LocationResult) {
                r.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        client.requestLocationUpdates(req, cb, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(cb) }
    }
}

/**
 * Drives along a route geometry, emitting a fix per second. Used in demo mode (and with the
 * "Simulate drive" setting) so navigation can be shown without moving.
 */
object DriveSimulator {
    fun drive(geometry: RouteGeometry, speedKmh: Double, startAlongM: Double = 0.0, tickMs: Long = 1_000): Flow<Fix> = flow {
        var along = startAlongM
        val mps = speedKmh / 3.6
        while (along <= geometry.lengthM) {
            val (p, brg) = geometry.pointAt(along)
            // Slow down near the end for a natural arrival.
            val left = geometry.lengthM - along
            val v = if (left < 150) maxOf(4.0, mps * left / 150) else mps
            emit(Fix(p, v.toFloat(), brg.toFloat(), 5f, System.currentTimeMillis(), simulated = true))
            delay(tickMs)
            along += v * tickMs / 1000.0
        }
        val (end, brg) = geometry.pointAt(geometry.lengthM)
        emit(Fix(end, 0f, brg.toFloat(), 5f, System.currentTimeMillis(), simulated = true))
    }
}
