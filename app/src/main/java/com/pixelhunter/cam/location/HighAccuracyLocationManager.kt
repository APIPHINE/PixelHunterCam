package com.pixelhunter.cam.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * High-accuracy location manager for precise GPS coordinates.
 * 
 * Features:
 * - Requests fresh high-accuracy location (not stale cached location)
 * - Configurable accuracy requirements
 * - Timeout handling
 * - Fallback to last known location if fresh location unavailable
 * - Accuracy reporting for quality assessment
 */
class HighAccuracyLocationManager(private val context: Context) {

    companion object {
        private const val TAG = "HighAccuracyLocation"
        
        // Accuracy thresholds
        const val ACCURACY_EXCELLENT = 5f      // < 5 meters
        const val ACCURACY_GOOD = 10f          // < 10 meters
        const val ACCURACY_ACCEPTABLE = 20f    // < 20 meters
        const val ACCURACY_POOR = 50f          // > 50 meters (reject)
        
        // Default timeout for location request
        private const val DEFAULT_TIMEOUT_MS = 15000L  // 15 seconds
        
        // Update intervals
        private const val UPDATE_INTERVAL_MS = 500L     // 500ms between updates
        private const val FASTEST_INTERVAL_MS = 250L    // 250ms fastest
    }

    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val locationSettingsClient = LocationServices.getSettingsClient(context)
    
    data class LocationResult(
        val location: Location?,
        val accuracy: Float,
        val accuracyRating: AccuracyRating,
        val isFresh: Boolean,
        val provider: String
    )
    
    enum class AccuracyRating {
        EXCELLENT,   // < 5m
        GOOD,        // < 10m
        ACCEPTABLE,  // < 20m
        POOR,        // < 50m
        UNACCEPTABLE // > 50m or null
    }

    /**
     * Get high-accuracy location with timeout.
     * 
     * @param timeoutMs Maximum time to wait for location
     * @param requireAccuracy Minimum accuracy rating required (returns null if not met)
     * @return LocationResult with location and quality info
     */
    @SuppressLint("MissingPermission")
    suspend fun getHighAccuracyLocation(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        requireAccuracy: AccuracyRating = AccuracyRating.ACCEPTABLE
    ): LocationResult = withContext(Dispatchers.IO) {
        
        // First, try to get a quick last known location as fallback
        val lastLocation = try {
            fusedLocationClient.lastLocation.await()
        } catch (e: Exception) {
            Log.w(TAG, "Could not get last location", e)
            null
        }
        
        // Check if last location is good enough
        if (lastLocation != null && isLocationFresh(lastLocation) && 
            lastLocation.accuracy <= getAccuracyThreshold(requireAccuracy)) {
            Log.d(TAG, "Using fresh last location: ${lastLocation.accuracy}m")
            return@withContext createLocationResult(lastLocation, true)
        }
        
        // Request fresh high-accuracy location
        Log.d(TAG, "Requesting fresh high-accuracy location...")
        
        val locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            UPDATE_INTERVAL_MS
        ).apply {
            setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            setMaxUpdateDelayMillis(timeoutMs)
            setMaxUpdates(3)  // Get up to 3 updates, take the best
        }.build()
        
        try {
            withTimeout(timeoutMs) {
                val bestLocation = awaitBestLocation(locationRequest)
                
                if (bestLocation != null) {
                    val result = createLocationResult(bestLocation, true)
                    if (result.accuracyRating <= requireAccuracy) {
                        return@withTimeout result
                    }
                }
                
                // Fall back to last location if fresh location not accurate enough
                if (lastLocation != null) {
                    Log.w(TAG, "Fresh location not accurate enough, using last location")
                    return@withTimeout createLocationResult(lastLocation, false)
                }
                
                return@withTimeout LocationResult(null, Float.MAX_VALUE, AccuracyRating.UNACCEPTABLE, false, "none")
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Location request timed out after ${timeoutMs}ms")
            
            // Return last location if we have one
            if (lastLocation != null) {
                return@withContext createLocationResult(lastLocation, false).also {
                    Log.w(TAG, "Using stale last location due to timeout: ${lastLocation.accuracy}m")
                }
            }
            
            return@withContext LocationResult(null, Float.MAX_VALUE, AccuracyRating.UNACCEPTABLE, false, "timeout")
        }
    }
    
    /**
     * Wait for and return the most accurate location from multiple updates.
     */
    private suspend fun awaitBestLocation(locationRequest: LocationRequest): Location? = 
        suspendCoroutine { continuation ->
            val locations = mutableListOf<Location>()
            var callback: LocationCallback? = null
            
            callback = object : LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                    val location = result.lastLocation
                    if (location != null) {
                        Log.d(TAG, "Received location: ${location.accuracy}m accuracy")
                        locations.add(location)
                        
                        // If we got an excellent location, return immediately
                        if (location.accuracy <= ACCURACY_EXCELLENT) {
                            fusedLocationClient.removeLocationUpdates(this)
                            continuation.resume(location)
                            return
                        }
                        
                        // Otherwise, collect up to 3 locations and return the best
                        if (locations.size >= 3) {
                            fusedLocationClient.removeLocationUpdates(this)
                            val best = locations.minByOrNull { it.accuracy }
                            continuation.resume(best)
                        }
                    }
                }
                
                override fun onLocationAvailability(availability: LocationAvailability) {
                    if (!availability.isLocationAvailable) {
                        Log.w(TAG, "Location not available")
                    }
                }
            }
            
            try {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    callback,
                    Looper.getMainLooper()
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request location updates", e)
                continuation.resume(null)
            }
            
            // Safety: remove callback if coroutine is cancelled
            continuation.context[Job]?.invokeOnCompletion {
                callback?.let { fusedLocationClient.removeLocationUpdates(it) }
            }
        }
    
    /**
     * Check if location is fresh (less than 2 minutes old).
     */
    private fun isLocationFresh(location: Location): Boolean {
        val ageMs = System.currentTimeMillis() - location.time
        return ageMs < 120_000  // 2 minutes
    }
    
    /**
     * Create a LocationResult from a Location object.
     */
    private fun createLocationResult(location: Location, isFresh: Boolean): LocationResult {
        val rating = when {
            location.accuracy <= ACCURACY_EXCELLENT -> AccuracyRating.EXCELLENT
            location.accuracy <= ACCURACY_GOOD -> AccuracyRating.GOOD
            location.accuracy <= ACCURACY_ACCEPTABLE -> AccuracyRating.ACCEPTABLE
            location.accuracy <= ACCURACY_POOR -> AccuracyRating.POOR
            else -> AccuracyRating.UNACCEPTABLE
        }
        
        return LocationResult(
            location = location,
            accuracy = location.accuracy,
            accuracyRating = rating,
            isFresh = isFresh,
            provider = location.provider ?: "unknown"
        )
    }
    
    /**
     * Get the accuracy threshold in meters for a given rating.
     */
    private fun getAccuracyThreshold(rating: AccuracyRating): Float {
        return when (rating) {
            AccuracyRating.EXCELLENT -> ACCURACY_EXCELLENT
            AccuracyRating.GOOD -> ACCURACY_GOOD
            AccuracyRating.ACCEPTABLE -> ACCURACY_ACCEPTABLE
            AccuracyRating.POOR -> ACCURACY_POOR
            AccuracyRating.UNACCEPTABLE -> Float.MAX_VALUE
        }
    }
    
    /**
     * Check if location services are enabled.
     */
    suspend fun isLocationEnabled(): Boolean {
        return try {
            val locationSettingsResponse = locationSettingsClient.checkLocationSettings(
                LocationSettingsRequest.Builder()
                    .addLocationRequest(LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build())
                    .build()
            ).await()
            locationSettingsResponse.locationSettingsStates?.isLocationUsable == true
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Format location for display.
     */
    fun formatLocation(location: Location): String {
        return String.format("%.6f, %.6f (±%.1fm)", 
            location.latitude, location.longitude, location.accuracy)
    }
    
    /**
     * Get human-readable accuracy description.
     */
    fun getAccuracyDescription(rating: AccuracyRating): String {
        return when (rating) {
            AccuracyRating.EXCELLENT -> "Excellent (< 5m)"
            AccuracyRating.GOOD -> "Good (< 10m)"
            AccuracyRating.ACCEPTABLE -> "Acceptable (< 20m)"
            AccuracyRating.POOR -> "Poor (< 50m)"
            AccuracyRating.UNACCEPTABLE -> "Unacceptable (> 50m)"
        }
    }
}
