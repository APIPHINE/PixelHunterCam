package com.pixelhunter.cam.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.pixelhunter.cam.camera.EnhancedCameraController
import com.pixelhunter.cam.analysis.EnhancedFrameAnalyzer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Manages saving images to user-accessible storage with full EXIF and metadata.
 * 
 * Features:
 * - Saves to Pictures/PixelHunter/ (public, visible in Photos app)
 * - Writes comprehensive EXIF data (GPS, camera settings, timestamp)
 * - Generates JSON sidecar for dataset annotation
 * - Supports both MediaStore (Android 10+) and legacy storage
 */
class MediaStoreManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaStoreManager"
        private const val FOLDER_NAME = "PixelHunter"
        private const val JPEG_QUALITY = 95
    }

    data class SaveResult(
        val imageUri: Uri,
        val imagePath: String,
        val jsonPath: String,
        val fileName: String
    )

    data class CaptureMetadata(
        // What we requested
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?,
        val accuracyMeters: Float?,
        val timestamp: Long,
        val timestampIso: String,
        val timezone: String,
        val iso: Int,
        val shutterSpeedNs: Long,
        val whiteBalanceK: Int,
        val focalLength: Float?,
        val aperture: Float?,
        val flashMode: Int,
        val flashFired: Boolean,
        val zoomRatio: Float,
        val focusDistanceDiopters: Float,
        val exposureBias: Float,
        val deviceOrientation: Int,
        val sessionLabel: String,
        val sessionId: String,
        // What the sensor actually used — read from TotalCaptureResult
        val actualIso: Int = iso,
        val actualShutterNs: Long = shutterSpeedNs,
        val actualAeModeOff: Boolean = false,
        val actualAwbModeOff: Boolean = false
    ) {
        val deviceOrientationDegrees: Int get() = when (deviceOrientation) {
            Surface.ROTATION_0   -> 0
            Surface.ROTATION_90  -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else                 -> 0
        }
        
        val settingsVerified: Boolean get() = actualAeModeOff && actualAwbModeOff
    }

    /**
     * Save image with full metadata to public Pictures folder.
     * Returns URI that can be used to access the saved image.
     */
    suspend fun saveImage(
        bitmap: Bitmap,
        metadata: CaptureMetadata,
        analysis: EnhancedFrameAnalyzer.EnhancedAnalysisResult
    ): SaveResult = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val fileName = generateFileName(timestamp)
        
        // Create the image in MediaStore
        val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveImageMediaStore(bitmap, fileName, timestamp)
        } else {
            saveImageLegacy(bitmap, fileName)
        }

        // Write EXIF data
        writeExifData(imageUri, metadata)

        // Generate JSON sidecar
        val jsonUri = saveJsonSidecar(imageUri, fileName, metadata, analysis)

        // Get actual file path for database
        val imagePath = getPathFromUri(imageUri) ?: imageUri.toString()
        val jsonPath = getPathFromUri(jsonUri) ?: jsonUri.toString()

        SaveResult(imageUri, imagePath, jsonPath, fileName)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveImageMediaStore(bitmap: Bitmap, fileName: String, timestamp: Long): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/$FOLDER_NAME")
            put(MediaStore.Images.Media.DATE_TAKEN, timestamp)
            put(MediaStore.Images.Media.DATE_ADDED, timestamp / 1000)
            put(MediaStore.Images.Media.DATE_MODIFIED, timestamp / 1000)
            put(MediaStore.Images.Media.IS_PENDING, 1)  // Mark as pending during write
        }

        val resolver = context.contentResolver
        var uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create MediaStore entry")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
            }
            
            // Mark as not pending (visible to other apps)
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
        } catch (e: Exception) {
            // Clean up on failure
            resolver.delete(uri, null, null)
            throw e
        }

        return uri
    }

    private fun saveImageLegacy(bitmap: Bitmap, fileName: String): Uri {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val pixelHunterDir = File(picturesDir, FOLDER_NAME)
        if (!pixelHunterDir.exists()) {
            pixelHunterDir.mkdirs()
        }

        val imageFile = File(pixelHunterDir, "$fileName.jpg")
        FileOutputStream(imageFile).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        }

        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
    }

    /**
     * Write comprehensive EXIF data to the saved image.
     */
    private fun writeExifData(imageUri: Uri, metadata: CaptureMetadata) {
        try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(imageUri, "rw")
                ?: return
            
            parcelFileDescriptor.use { pfd ->
                ExifInterface(pfd.fileDescriptor).apply {
                    // Timestamp
                    val dateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val dateString = dateFormat.format(Date(metadata.timestamp))
                    setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateString)
                    setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateString)

                    // GPS Data
                    metadata.latitude?.let { lat ->
                        metadata.longitude?.let { lng ->
                            setAttribute(ExifInterface.TAG_GPS_LATITUDE, convertToExifLatLong(lat))
                            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (lat >= 0) "N" else "S")
                            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, convertToExifLatLong(lng))
                            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (lng >= 0) "E" else "W")
                            
                            metadata.altitude?.let { alt ->
                                setAttribute(ExifInterface.TAG_GPS_ALTITUDE, Math.abs(alt).toString())
                                setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (alt >= 0) "0" else "1")
                            }
                        }
                    }

                    // Camera Settings
                    if (metadata.iso > 0) {
                        setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, metadata.iso.toString())
                    }
                    
                    if (metadata.shutterSpeedNs > 0) {
                        val exposureTime = metadata.shutterSpeedNs / 1_000_000_000.0
                        setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format("%.6f", exposureTime))
                    }
                    
                    metadata.focalLength?.let {
                        setAttribute(ExifInterface.TAG_FOCAL_LENGTH, it.toString())
                    }
                    
                    metadata.aperture?.let {
                        setAttribute(ExifInterface.TAG_F_NUMBER, it.toString())
                    }

                    // Device Info
                    setAttribute(ExifInterface.TAG_MAKE, android.os.Build.MANUFACTURER)
                    setAttribute(ExifInterface.TAG_MODEL, android.os.Build.MODEL)
                    
                    // Software version for pipeline version-gating
                    setAttribute(ExifInterface.TAG_SOFTWARE, "PixelHunterCam/0.4.0")
                    
                    // Orientation - critical for correct bounding box annotation
                    val exifOrientation = when (metadata.deviceOrientation) {
                        Surface.ROTATION_0   -> ExifInterface.ORIENTATION_ROTATE_90   // portrait
                        Surface.ROTATION_90  -> ExifInterface.ORIENTATION_NORMAL      // landscape right
                        Surface.ROTATION_180 -> ExifInterface.ORIENTATION_ROTATE_270  // portrait upside down
                        Surface.ROTATION_270 -> ExifInterface.ORIENTATION_ROTATE_180  // landscape left
                        else                 -> ExifInterface.ORIENTATION_NORMAL
                    }
                    setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())

                    // Image Description (for dataset labeling)
                    setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, 
                        "PixelHunter capture | ${metadata.sessionLabel} | WB:${metadata.whiteBalanceK}K")

                    // User Comment (JSON metadata for ML)
                    val userComment = buildString {
                        append("{")
                        append("\"zoom_ratio\":${metadata.zoomRatio},")
                        append("\"focus_distance_m\":${if (metadata.focusDistanceDiopters > 0f) 1f / metadata.focusDistanceDiopters else 0f},")
                        append("\"session_id\":\"${metadata.sessionId}\"")
                        append("}")
                    }
                    setAttribute(ExifInterface.TAG_USER_COMMENT, userComment)

                    saveAttributes()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write EXIF data", e)
        }
    }

    /**
     * Save JSON sidecar file with comprehensive dataset annotation.
     */
    private fun saveJsonSidecar(
        imageUri: Uri,
        fileName: String,
        metadata: CaptureMetadata,
        analysis: EnhancedFrameAnalyzer.EnhancedAnalysisResult
    ): Uri {
        val jsonContent = buildJsonSidecar(imageUri, metadata, analysis)
        val jsonFileName = "$fileName.json"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveJsonMediaStore(jsonContent, jsonFileName)
        } else {
            saveJsonLegacy(jsonContent, jsonFileName)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveJsonMediaStore(jsonContent: String, fileName: String): Uri {
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$FOLDER_NAME")
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Failed to create JSON MediaStore entry")

        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(jsonContent.toByteArray())
        }

        return uri
    }

    private fun saveJsonLegacy(jsonContent: String, fileName: String): Uri {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val pixelHunterDir = File(downloadsDir, FOLDER_NAME)
        if (!pixelHunterDir.exists()) {
            pixelHunterDir.mkdirs()
        }

        val jsonFile = File(pixelHunterDir, fileName)
        jsonFile.writeText(jsonContent)

        return Uri.fromFile(jsonFile)
    }

    private fun buildJsonSidecar(
        imageUri: Uri,
        metadata: CaptureMetadata,
        analysis: EnhancedFrameAnalyzer.EnhancedAnalysisResult
    ): String {
        val json = JSONObject()
        
        // Provenance
        json.put("app_version", "0.4.0")
        json.put("schema_version", 2)
        json.put("session_id", metadata.sessionId)
        json.put("session_label", metadata.sessionLabel)
        json.put("timestamp_iso", metadata.timestampIso)
        json.put("timestamp", metadata.timestamp)
        
        // GPS
        json.put("latitude", metadata.latitude)
        json.put("longitude", metadata.longitude)
        json.put("gps_accuracy_meters", metadata.accuracyMeters)
        json.put("gps_altitude_m", metadata.altitude)
        
        // Camera settings (actual values used, not just requested)
        json.put("iso", metadata.iso)
        json.put("shutter_speed_ns", metadata.shutterSpeedNs)
        json.put("shutter_speed_seconds", metadata.shutterSpeedNs / 1_000_000_000.0)
        json.put("white_balance_k", metadata.whiteBalanceK)
        json.put("focal_length_mm", metadata.focalLength)
        json.put("aperture_f", metadata.aperture)
        json.put("zoom_ratio", metadata.zoomRatio)
        json.put("focus_distance_m", if (metadata.focusDistanceDiopters > 0f) 1f / metadata.focusDistanceDiopters else 0f)
        json.put("exposure_bias_ev", metadata.exposureBias)
        json.put("flash_fired", metadata.flashFired)
        json.put("device_orientation_deg", metadata.deviceOrientationDegrees)
        
        // Settings verification
        json.put("settings_verified", metadata.settingsVerified)
        json.put("actual_iso", metadata.actualIso)
        json.put("actual_shutter_speed_ns", metadata.actualShutterNs)
        json.put("actual_shutter_speed_seconds", metadata.actualShutterNs / 1_000_000_000.0)
        
        // Frame quality analysis
        json.put("blur_score", analysis.blurScore)
        json.put("is_globally_blurry", analysis.isGloballyBlurry)
        json.put("is_locally_blurry", analysis.isLocallyBlurry)
        json.put("blurry_tile_ratio", analysis.blurryTileRatio)
        json.put("luminance", analysis.luminance)
        json.put("color_temperature_k", analysis.estimatedColorTempK)
        json.put("is_properly_exposed", analysis.luminance in 0.3f..0.7f)
        json.put("is_portrait", analysis.isPortrait)
        json.put("composition_score", analysis.compositionScore)
        
        // Histogram zones
        val hist = JSONObject().apply {
            put("blacks", analysis.exposureZones.blacks)
            put("shadows", analysis.exposureZones.shadows)
            put("midtones", analysis.exposureZones.midtones)
            put("highlights", analysis.exposureZones.highlights)
            put("whites", analysis.exposureZones.whites)
        }
        json.put("histogram", hist)
        
        // Clipping
        json.put("highlight_clipping", analysis.highlightClipping)
        json.put("shadow_clipping", analysis.shadowClipping)
        
        // Face regions
        val facesArray = JSONArray()
        analysis.faces.forEach { face ->
            val faceObj = JSONObject().apply {
                put("x", face.bounds.left)
                put("y", face.bounds.top)
                put("width", face.bounds.width())
                put("height", face.bounds.height())
                put("confidence", face.confidence)
                put("is_in_focus", face.isInFocus)
                put("is_properly_exposed", face.isProperlyExposed)
            }
            facesArray.put(faceObj)
        }
        json.put("faces", facesArray)
        json.put("face_count", analysis.faces.size)
        
        // Analysis flags
        val flagsArray = JSONArray()
        analysis.flags.forEach { flag ->
            val flagObj = JSONObject().apply {
                put("type", flag.type.name)
                put("severity", flag.severity.name)
                put("message", flag.message)
                put("suggest_api_review", flag.suggestApiReview)
            }
            flagsArray.put(flagObj)
        }
        json.put("flags", flagsArray)
        json.put("flag_count", analysis.flags.size)
        
        // Pipeline annotation hints
        val hints = JSONObject().apply {
            put("usable_for_training",
                !analysis.isGloballyBlurry &&
                analysis.highlightClipping < 0.05f &&
                analysis.shadowClipping < 0.15f)
            put("needs_exposure_review",
                analysis.flags.any { it.type.name == "EXPOSURE_DRIFT" })
            put("needs_wb_review",
                analysis.flags.any { it.type.name == "WHITE_BALANCE_DRIFT" })
            put("has_faces", analysis.faces.isNotEmpty())
            put("needs_privacy_review", analysis.faces.isNotEmpty())
            put("exposure_quality",
                when {
                    analysis.highlightClipping > 0.10f -> "overexposed"
                    analysis.shadowClipping > 0.20f -> "underexposed"
                    analysis.luminance in 0.3f..0.7f -> "good"
                    else -> "marginal"
                })
        }
        json.put("pipeline_hints", hints)
        
        // Device info
        json.put("device_manufacturer", android.os.Build.MANUFACTURER)
        json.put("device_model", android.os.Build.MODEL)
        json.put("android_version", android.os.Build.VERSION.RELEASE)
        
        return json.toString(2)
    }

    private fun buildJsonMetadata(metadata: CaptureMetadata): JSONObject {
        return JSONObject().apply {
            put("app", "PixelHunter")
            put("version", "1.0")
            put("session_id", metadata.sessionId)
            put("wb_k", metadata.whiteBalanceK)
            put("zoom", metadata.zoomRatio)
        }
    }

    private fun generateFileName(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        return "PH_${dateFormat.format(Date(timestamp))}"
    }

    private fun formatIsoTimestamp(timestamp: Long): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        isoFormat.timeZone = TimeZone.getTimeZone("UTC")
        return isoFormat.format(Date(timestamp))
    }

    private fun formatLocalTimestamp(timestamp: Long): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS")
        return isoFormat.format(Date(timestamp))
    }

    /**
     * Convert decimal degrees to EXIF format (degrees/minutes/seconds)
     */
    private fun convertToExifLatLong(coordinate: Double): String {
        val absCoord = Math.abs(coordinate)
        val degrees = absCoord.toInt()
        val minutesFull = (absCoord - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return "$degrees/1,$minutes/1,${(seconds * 10000).toInt()}/10000"
    }

    private fun getPathFromUri(uri: Uri): String? {
        return try {
            when (uri.scheme) {
                "file" -> uri.path
                "content" -> {
                    // For content URIs, return the URI string (path not directly accessible)
                    uri.toString()
                }
                else -> uri.toString()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get path from URI", e)
            null
        }
    }
}
