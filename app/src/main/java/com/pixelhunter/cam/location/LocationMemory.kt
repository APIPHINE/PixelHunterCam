package com.pixelhunter.cam.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.pixelhunter.cam.db.LocationImageCount
import com.pixelhunter.cam.db.PixelHunterDatabase
import com.pixelhunter.cam.db.ShootImage
import com.pixelhunter.cam.db.ShootLocation
import com.pixelhunter.cam.session.SessionSettings
import kotlinx.coroutines.tasks.await

/**
 * Manages location-based scene memory with high-accuracy GPS.
 * - Finds nearby past shoot locations (within ~50 meters)
 * - Stores settings used at each location
 * - Retrieves past images for ghost overlay
 * - Provides high-accuracy GPS for dataset annotation
 *
 * GPS unavailable policy:
 * - Images are NEVER silently dropped.
 * - When GPS is unavailable, images are saved under a sentinel
 *   "No GPS" location (lat=0, lng=0) so they can be reviewed/
 *   reassigned later. The caller receives a LocationSaveResult
 *   indicating whether GPS was available.
 */
class LocationMemory(private val context: Context) {

    private val TAG = "LocationMemory"
    private val db = PixelHunterDatabase.getInstance(context)
    private val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    private val highAccuracyManager = HighAccuracyLocationManager(context)

    // ~50 meters in degrees latitude (rough but fine for this use case)
    private val NEARBY_THRESHOLD_DEGREES = 0.0005

    // Sentinel location ID used when GPS is unavailable.
    @Volatile private var noGpsLocationId: Long? = null

    companion object {
        const val NO_GPS_LAT = 0.0
        const val NO_GPS_LNG = 0.0
        const val NO_GPS_LABEL = "No GPS — unassigned"
        const val NEARBY_RADIUS_METERS = 50f

        /**
         * Compute Haversine great-circle distance in meters between two coords.
         * Accurate to within ~0.5% — more than sufficient for 50m proximity.
         */
        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
            val R = 6_371_000.0  // Earth radius in metres
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = Math.sin(dLat / 2).let { it * it } +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLng / 2).let { it * it }
            return (R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))).toFloat()
        }
    }

    sealed class LocationSaveResult {
        data class WithGps(
            val locationId: Long,
            val location: HighAccuracyLocationManager.LocationResult
        ) : LocationSaveResult()
        
        data class WithoutGps(val locationId: Long) : LocationSaveResult()
    }

    data class NearbyResult(
        val location: ShootLocation,
        val distanceMeters: Float,
        val imageCount: Int,
        val suggestedSettings: SessionSettings?
    )

    // ─── High-Accuracy Location ────────────────────────────────────

    /**
     * Get high-accuracy location for image capture.
     * This should be called before capture to ensure fresh GPS data.
     */
    suspend fun getAccurateLocation(): HighAccuracyLocationManager.LocationResult {
        return highAccuracyManager.getHighAccuracyLocation(
            timeoutMs = 10000,  // 10 second timeout
            requireAccuracy = HighAccuracyLocationManager.AccuracyRating.ACCEPTABLE
        )
    }

    // ─── Find nearby locations ─────────────────────────────────────

    @SuppressLint("MissingPermission")
    suspend fun findNearbyLocations(): List<NearbyResult> {
        // Use high-accuracy location for finding nearby spots
        val locationResult = getAccurateLocation()
        val location = locationResult.location ?: return emptyList()

        val lat = location.latitude
        val lng = location.longitude

        // Bounding box: ~50m + 20% margin
        val latDelta = 0.00054   // ~60m in latitude degrees
        // lngDelta shrinks toward the poles: cos(lat) correction
        val lngDelta = 0.00054 / Math.cos(Math.toRadians(lat)).coerceAtLeast(0.1)

        val candidates = db.locationDao().findNearby(lat, lng, latDelta, lngDelta)

        // Exact Haversine filter — eliminates bounding-box corners
        val filtered = candidates
            .map { loc -> Pair(loc, haversineMeters(lat, lng, loc.lat, loc.lng)) }
            .filter { (_, dist) -> dist <= NEARBY_RADIUS_METERS }
            .sortedBy { (_, dist) -> dist }

        if (filtered.isEmpty()) return emptyList()

        // Batch count query — one DB round trip for all locations instead of N
        val ids = filtered.map { (loc, _) -> loc.id }
        val countMap = db.imageDao().countForLocations(ids).associate { it.locationId to it.count }

        return filtered.map { (loc, dist) ->
            val suggestedSettings = if (loc.lastIso > 0) {
                SessionSettings(
                    iso = loc.lastIso,
                    shutterSpeedNs = loc.lastShutterNs,
                    whiteBalanceKelvin = loc.lastWhiteBalanceK,
                    exposureCompensation = loc.lastExposureComp,
                    locationLabel = loc.label
                )
            } else null
            NearbyResult(loc, dist, countMap[loc.id] ?: 0, suggestedSettings)
        }
    }

    // ─── Resolve or create location for image save ─────────────────

    /**
     * Safe location resolver — never returns null.
     * Returns GPS-based LocationSaveResult.WithGps when available,
     * or LocationSaveResult.WithoutGps with a sentinel location ID otherwise.
     * Images are always saved; nothing is silently dropped.
     */
    suspend fun resolveOrCreateLocation(
        thumbnailPath: String,
        settings: SessionSettings,
        highAccuracyResult: HighAccuracyLocationManager.LocationResult
    ): LocationSaveResult {
        
        return if (highAccuracyResult.location != null && 
                   highAccuracyResult.accuracyRating <= HighAccuracyLocationManager.AccuracyRating.POOR) {
            // Use the high-accuracy location
            val gpsLocation = highAccuracyResult.location
            
            // Check if we already have a location here
            val existing = db.locationDao().findNearby(
                gpsLocation.latitude, gpsLocation.longitude,
                NEARBY_THRESHOLD_DEGREES, NEARBY_THRESHOLD_DEGREES
            ).firstOrNull()

            val locationId = existing?.id ?: saveLocation(
                lat = gpsLocation.latitude,
                lng = gpsLocation.longitude,
                altitude = gpsLocation.altitude,
                accuracy = gpsLocation.accuracy,
                label = generateLabel(gpsLocation.latitude, gpsLocation.longitude),
                thumbnailPath = thumbnailPath,
                settings = settings
            )
            
            LocationSaveResult.WithGps(locationId, highAccuracyResult)
        } else {
            // No GPS or unacceptable accuracy — save under sentinel location
            val sentinelId = getOrCreateNoGpsLocation(thumbnailPath, settings)
            Log.w(TAG, "Image saved to No-GPS sentinel location (id=$sentinelId)")
            LocationSaveResult.WithoutGps(sentinelId)
        }
    }

    private suspend fun getOrCreateNoGpsLocation(
        thumbnailPath: String,
        settings: SessionSettings
    ): Long {
        noGpsLocationId?.let { return it }

        // Check DB for existing sentinel
        val existing = db.locationDao().findNearby(
            NO_GPS_LAT, NO_GPS_LNG,
            0.00001, 0.00001  // Tight radius — exact sentinel match only
        ).firstOrNull { it.label == NO_GPS_LABEL }

        return if (existing != null) {
            existing.id.also { noGpsLocationId = it }
        } else {
            saveLocation(NO_GPS_LAT, NO_GPS_LNG, 0.0, 999f, NO_GPS_LABEL, thumbnailPath, settings)
                .also { noGpsLocationId = it }
        }
    }

    // ─── Save a new shoot location ─────────────────────────────────

    suspend fun saveLocation(
        lat: Double,
        lng: Double,
        altitude: Double = 0.0,
        accuracy: Float = 999f,
        label: String,
        thumbnailPath: String,
        settings: SessionSettings
    ): Long {
        val location = ShootLocation(
            lat = lat,
            lng = lng,
            label = label,
            createdAt = System.currentTimeMillis(),
            thumbnailPath = thumbnailPath,
            lastIso = settings.iso,
            lastShutterNs = settings.shutterSpeedNs,
            lastWhiteBalanceK = settings.whiteBalanceKelvin,
            lastExposureComp = settings.exposureCompensation
        )
        return db.locationDao().insert(location)
    }

    // ─── Update settings for a location ───────────────────────────

    suspend fun updateLocationSettings(locationId: Long, settings: SessionSettings) {
        val loc = db.locationDao().getById(locationId) ?: return
        db.locationDao().update(loc.copy(
            lastIso = settings.iso,
            lastShutterNs = settings.shutterSpeedNs,
            lastWhiteBalanceK = settings.whiteBalanceKelvin,
            lastExposureComp = settings.exposureCompensation
        ))
    }

    // ─── Get past images for ghost overlay ────────────────────────

    suspend fun getPastImagesForLocation(locationId: Long): List<ShootImage> {
        return db.imageDao().getRecentForLocation(locationId)
    }

    // ─── Save a captured image ────────────────────────────────────

    suspend fun saveImage(image: ShootImage): Long {
        return db.imageDao().insert(image)
    }

    // ─── Auto-generate location label ─────────────────────────────

    fun generateLabel(lat: Double, lng: Double): String {
        val latStr = String.format("%.4f", Math.abs(lat))
        val lngStr = String.format("%.4f", Math.abs(lng))
        val latDir = if (lat >= 0) "N" else "S"
        val lngDir = if (lng >= 0) "E" else "W"
        return "Location $latStr°$latDir $lngStr°$lngDir"
    }
}
