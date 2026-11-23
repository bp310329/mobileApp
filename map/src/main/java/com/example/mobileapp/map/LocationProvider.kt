package com.example.mobileapp.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await

/** Snapshot of the device position, independent of Android/MapLibre types. */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy in meters, or `null` when unknown. */
    val accuracyMeters: Float?,
    /** Direction of travel in degrees, or `null` when unknown. */
    val bearing: Float?,
    val timestampMillis: Long,
)

fun Location.toUserLocation() = UserLocation(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = if (hasAccuracy()) accuracy else null,
    bearing = if (hasBearing()) bearing else null,
    timestampMillis = time,
)

/**
 * Device location source backed by the fused location provider.
 *
 * - [locationUpdates] streams real-time positions while collected.
 * - [currentLocation] always holds the latest known position.
 * - [getCurrentLocation] fetches a single fresh fix.
 *
 * Uses GPS accuracy when fine location is granted and falls back to network accuracy
 * for coarse-only grants.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)

    private val _currentLocation = MutableStateFlow<UserLocation?>(null)
    val currentLocation: StateFlow<UserLocation?> = _currentLocation.asStateFlow()

    fun hasLocationPermission(): Boolean = hasFinePermission() || hasCoarsePermission()

    /** `true` while a debug location set by [setLocationOverride] replaces the real position. */
    val isLocationOverridden: Boolean get() = debugOverride.value != null

    /**
     * Debug aid: pretends the device is at the given position. While set, real GPS updates are
     * ignored (and not requested) and every consumer — map marker, [currentLocation],
     * [getCurrentLocation] — sees this position instead. Kept process-wide so it survives
     * configuration changes.
     */
    fun setLocationOverride(latitude: Double, longitude: Double) {
        val location = Location(OVERRIDE_PROVIDER).apply {
            this.latitude = latitude
            this.longitude = longitude
            accuracy = OVERRIDE_ACCURACY_METERS
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        debugOverride.value = location
        publish(location)
    }

    /** Returns to the real device position; [currentLocation] is empty until the next real fix. */
    fun clearLocationOverride() {
        if (debugOverride.value == null) return
        debugOverride.value = null
        _currentLocation.value = null
    }

    /** Fetches a single fresh fix, or `null` when unavailable or permission is missing. */
    @SuppressLint("MissingPermission") // checked by hasLocationPermission()
    suspend fun getCurrentLocation(): Location? {
        debugOverride.value?.let { return it.also(::publish) }
        if (!hasLocationPermission()) return null

        val cancellationTokenSource = CancellationTokenSource()
        return try {
            fusedLocationClient.getCurrentLocation(priority(), cancellationTokenSource.token)
                .await()
                ?.also(::publish)
        } catch (e: CancellationException) {
            cancellationTokenSource.cancel()
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get current location", e)
            null
        }
    }

    /**
     * Real-time location updates. Updates are requested when collection starts and removed when
     * it stops, so collect it only while the UI is visible (e.g. inside `repeatOnLifecycle`).
     * Emits the last known position first, if there is one. Without location permission it emits
     * nothing. While a debug override is set, emits only that position (and switches back to real
     * updates when it's cleared).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun locationUpdates(): Flow<Location> = debugOverride.flatMapLatest { override ->
        if (override != null) flowOf(override) else deviceLocationUpdates()
    }

    @SuppressLint("MissingPermission") // checked by hasLocationPermission()
    private fun deviceLocationUpdates(): Flow<Location> = callbackFlow {
        if (!hasLocationPermission()) {
            close()
            return@callbackFlow
        }

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { location ->
                    publish(location)
                    trySend(location)
                }
            }
        }

        // Show the last known position immediately, before the first fresh fix arrives.
        fusedLocationClient.lastLocation.addOnSuccessListener { last ->
            if (last != null && _currentLocation.value == null) {
                publish(last)
                trySend(last)
            }
        }

        val request = LocationRequest.Builder(priority(), UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_UPDATE_INTERVAL_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { e -> close(e) }

        awaitClose { fusedLocationClient.removeLocationUpdates(callback) }
    }

    private fun publish(location: Location) {
        _currentLocation.value = location.toUserLocation()
    }

    private fun priority() =
        if (hasFinePermission()) Priority.PRIORITY_HIGH_ACCURACY
        else Priority.PRIORITY_BALANCED_POWER_ACCURACY

    private fun hasFinePermission() = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    private fun hasCoarsePermission() = granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Permissions the host should request before calling [AppMapView.enableUserLocation]. */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        /** Debug location override, shared by all instances (see [setLocationOverride]). */
        private val debugOverride = MutableStateFlow<Location?>(null)
        private const val OVERRIDE_PROVIDER = "debug-override"
        private const val OVERRIDE_ACCURACY_METERS = 5f

        private const val TAG = "LocationProvider"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val FASTEST_UPDATE_INTERVAL_MS = 500L
    }
}
