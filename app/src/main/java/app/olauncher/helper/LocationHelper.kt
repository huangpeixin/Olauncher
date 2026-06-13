package app.olauncher.helper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Helper to get device location via GPS/Network.
 * Uses LocationManager (no Play Services dependency).
 * Compatible with API 24+.
 */
class LocationHelper(private val context: Context) {

    companion object {
        private const val TAG = "LocationHelper"
        private const val LOCATION_TIMEOUT_MS = 15_000L
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    /**
     * Check if location permission is granted.
     */
    fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Get location. Returns (lat, lng) or null if unavailable.
     * Tries last known location first (fast), then requests a fresh fix with timeout.
     */
    suspend fun getLocation(): Pair<Double, Double>? = suspendCancellableCoroutine { cont ->
        if (!hasLocationPermission()) {
            Log.w(TAG, "No location permission")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // Try last known location first (fast path)
        val lastLocation = getLastKnownLocation()
        if (lastLocation != null) {
            Log.d(TAG, "Using last known location: ${lastLocation.latitude}, ${lastLocation.longitude}")
            cont.resume(Pair(lastLocation.latitude, lastLocation.longitude))
            return@suspendCancellableCoroutine
        }

        // Request a fresh location fix
        try {
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    try { locationManager.removeUpdates(this) } catch (_: Exception) {}
                    Log.d(TAG, "Got fresh location: ${location.latitude}, ${location.longitude}")
                    cont.resume(Pair(location.latitude, location.longitude))
                }

                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            // Try GPS first, then Network
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            ).filter { provider ->
                try { locationManager.isProviderEnabled(provider) } catch (_: Exception) { false }
            }

            if (providers.isEmpty()) {
                Log.w(TAG, "No location providers enabled")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }

            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    Log.w(TAG, "SecurityException for provider $provider")
                }
            }

            // Timeout fallback
            android.os.Handler(Looper.getMainLooper()).postDelayed({
                if (!cont.isCompleted) {
                    try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
                    Log.w(TAG, "Location request timed out")
                    cont.resume(null)
                }
            }, LOCATION_TIMEOUT_MS)

            cont.invokeOnCancellation {
                try { locationManager.removeUpdates(listener) } catch (_: Exception) {}
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException requesting location: ${e.message}")
            cont.resume(null)
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location: ${e.message}")
            cont.resume(null)
        }
    }

    /**
     * Get last known location from any provider.
     */
    private fun getLastKnownLocation(): Location? {
        if (!hasLocationPermission()) return null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val location = locationManager.getLastKnownLocation(provider)
                    if (location != null) return location
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SecurityException for provider $provider")
            }
        }
        return null
    }
}
