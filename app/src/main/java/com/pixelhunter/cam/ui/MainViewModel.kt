package com.pixelhunter.cam.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pixelhunter.cam.analysis.EnhancedFrameAnalyzer
import com.pixelhunter.cam.camera.EnhancedCameraController
import com.pixelhunter.cam.db.ShootImage
import com.pixelhunter.cam.location.HighAccuracyLocationManager
import com.pixelhunter.cam.location.LocationMemory
import com.pixelhunter.cam.session.SessionManager
import com.pixelhunter.cam.session.SessionSettings
import com.pixelhunter.cam.storage.MediaStoreManager
import com.pixelhunter.cam.ui.view.GridOverlayView
import com.pixelhunter.cam.util.ImageLoader
import com.pixelhunter.cam.util.MemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.view.PreviewView
import android.view.MotionEvent

/**
 * Enhanced ViewModel with:
 * - MediaStore integration for public folder saving
 * - High-accuracy GPS with quality tracking
 * - EXIF metadata preservation
 * - JSON sidecar generation for dataset annotation
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val sessionManager = SessionManager()
    val locationMemory: LocationMemory by lazy { LocationMemory(application) }
    val cameraController: EnhancedCameraController by lazy { 
        EnhancedCameraController(application, sessionManager) 
    }
    private val mediaStoreManager: MediaStoreManager by lazy {
        MediaStoreManager(application)
    }

    private val _uiState = MutableStateFlow(EnhancedCameraUiState())
    val uiState: StateFlow<EnhancedCameraUiState> = _uiState

    val cameraState: StateFlow<EnhancedCameraController.CameraState> = cameraController.cameraState

    private val _gridMode = MutableStateFlow(GridOverlayView.GridMode.RULE_OF_THIRDS)
    val gridMode: StateFlow<GridOverlayView.GridMode> = _gridMode

    private var histogramVisible = false
    
    // Session tracking
    private var currentSessionId = generateSessionId()

    // ─── Camera Control ───────────────────────────────────────────

    suspend fun startCamera(
        lifecycleOwner: androidx.lifecycle.LifecycleOwner,
        previewView: PreviewView
    ) {
        cameraController.startCamera(lifecycleOwner, previewView) { state ->
            // Metadata updates handled by StateFlow
        }
    }

    fun rebindCamera(lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) {
        cameraController.rebindCamera(lifecycleOwner, previewView)
    }

    fun handleTapToFocus(event: MotionEvent, previewView: PreviewView) {
        cameraController.handleTapToFocus(event, previewView)
    }

    // ─── Zoom Control ─────────────────────────────────────────────

    fun setZoom(zoomRatio: Float) {
        cameraController.setZoom(zoomRatio)
    }

    fun getMaxZoom(): Float = cameraController.getMaxZoom()

    // ─── Lens Switching ───────────────────────────────────────────

    fun switchLens(): Boolean {
        return cameraController.switchLens()
    }

    fun getCurrentLensName(): String = cameraState.value.activeCameraName

    fun getAvailableLensNames(): List<String> = cameraController.getAvailableLensNames()

    // ─── Flash Control ────────────────────────────────────────────

    fun setFlashMode(mode: Int) {
        cameraController.setFlashMode(mode)
    }

    fun getFlashMode(): Int = cameraController.getFlashMode()

    // ─── Grid Control ─────────────────────────────────────────────

    fun cycleGridMode(): GridOverlayView.GridMode {
        val values = GridOverlayView.GridMode.values()
        val nextIndex = (_gridMode.value.ordinal + 1) % values.size
        _gridMode.value = values[nextIndex]
        return _gridMode.value
    }

    // ─── Histogram Control ────────────────────────────────────────

    fun toggleHistogram(): Boolean {
        histogramVisible = !histogramVisible
        return histogramVisible
    }

    fun isHistogramVisible(): Boolean = histogramVisible

    // ─── Location Management ──────────────────────────────────────

    fun checkNearbyLocations() {
        viewModelScope.launch {
            val nearby = locationMemory.findNearbyLocations()
            if (nearby.isNotEmpty()) {
                _uiState.value = _uiState.value.copy(
                    nearbyLocations = nearby,
                    showLocationPrompt = true
                )
            }
        }
    }

    fun applyLocationSettings(result: LocationMemory.NearbyResult) {
        result.suggestedSettings?.let { suggested ->
            sessionManager.lockSession(
                iso = suggested.iso,
                shutterSpeedNs = suggested.shutterSpeedNs,
                whiteBalanceKelvin = suggested.whiteBalanceKelvin,
                exposureCompensation = suggested.exposureCompensation,
                baselineLuminance = -1f,
                baselineColorTemp = -1f,
                locationLabel = result.location.label
            )
            _uiState.value = _uiState.value.copy(
                activeLocationId = result.location.id,
                showLocationPrompt = false,
                statusMessage = "Settings restored for: ${result.location.label}"
            )
        }
        loadOverlayImages(result.location.id)
    }

    // ─── Capture with Full Metadata ───────────────────────────────

    fun capturePhoto() {
        viewModelScope.launch {
            android.util.Log.d("Capture", "=== Starting capture ===")
            _uiState.value = _uiState.value.copy(isCapturing = true, statusMessage = "Capturing...")
            
            var capturedBitmap: Bitmap? = null
            
            try {
                // Step 1: Capture the photo immediately (don't wait for GPS)
                android.util.Log.d("Capture", "Step 1: Taking photo...")
                val outputDir = getOutputDir()
                val captureResult = cameraController.capturePhoto(outputDir)
                capturedBitmap = captureResult.bitmap
                android.util.Log.d("Capture", "Step 1: Photo taken successfully")

                // Step 2: Get GPS in parallel with image processing (shorter timeout for indoor use)
                val locationDeferred = async(Dispatchers.IO) {
                    try {
                        withTimeout(3000) { // 3 second timeout for GPS
                            locationMemory.getAccurateLocation()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("Capture", "GPS acquisition failed or timed out: ${e.message}")
                        null
                    }
                }

                // Step 3: Analyze the image while GPS is being acquired
                android.util.Log.d("Capture", "Step 3: Analyzing image...")
                val analysis = withContext(Dispatchers.Default) {
                    EnhancedFrameAnalyzer.analyze(captureResult.bitmap, sessionManager)
                }
                android.util.Log.d("Capture", "Step 3: Analysis complete. Flags: ${analysis.flags.size}")

                // Step 4: Lock session from first shot if not already locked
                if (!sessionManager.settings.value.isLocked) {
                    android.util.Log.d("Capture", "Step 4: Locking session from first shot...")
                    lockFromFirstShot(analysis.luminance, analysis.estimatedColorTempK)
                }

                // Step 5: Wait for GPS result (or null if timed out)
                android.util.Log.d("Capture", "Step 5: Waiting for GPS...")
                val locationResult = locationDeferred.await()
                android.util.Log.d("Capture", "Step 5: GPS result received: ${locationResult?.accuracyRating}")
                
                val gpsStatus = when (locationResult?.accuracyRating) {
                    HighAccuracyLocationManager.AccuracyRating.EXCELLENT -> "📡 GPS: Excellent"
                    HighAccuracyLocationManager.AccuracyRating.GOOD -> "📡 GPS: Good"
                    HighAccuracyLocationManager.AccuracyRating.ACCEPTABLE -> "📡 GPS: OK"
                    HighAccuracyLocationManager.AccuracyRating.POOR -> "⚠️ GPS: Poor"
                    else -> if (locationResult?.location != null) "⚠️ GPS: Weak signal" else "📍 No GPS (indoors)"
                }
                _uiState.value = _uiState.value.copy(statusMessage = gpsStatus)

                // Step 6: Save with full metadata to MediaStore
                val settings = sessionManager.settings.value
                val now = System.currentTimeMillis()
                val metadata = MediaStoreManager.CaptureMetadata(
                    latitude = locationResult?.location?.latitude,
                    longitude = locationResult?.location?.longitude,
                    altitude = locationResult?.location?.altitude,
                    accuracyMeters = locationResult?.accuracy ?: Float.MAX_VALUE,
                    timestamp = now,
                    timestampIso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }.format(java.util.Date(now)),
                    timezone = TimeZone.getDefault().id,
                    iso = settings.iso,
                    shutterSpeedNs = settings.shutterSpeedNs,
                    focalLength = captureResult.metadata.focalLength,
                    aperture = captureResult.metadata.aperture,
                    whiteBalanceK = settings.whiteBalanceKelvin,
                    flashMode = cameraController.getFlashMode(),
                    flashFired = false,
                    zoomRatio = cameraState.value.currentZoom,
                    focusDistanceDiopters = 0f,
                    exposureBias = 0f,
                    deviceOrientation = getDeviceRotation(),
                    sessionLabel = settings.locationLabel,
                    sessionId = currentSessionId,
                    actualIso = captureResult.metadata.actualIso,
                    actualShutterNs = captureResult.metadata.actualShutterNs,
                    actualAeModeOff = captureResult.metadata.actualAeModeOff,
                    actualAwbModeOff = captureResult.metadata.actualAwbModeOff
                )

                android.util.Log.d("Capture", "Step 6: Saving to MediaStore...")
                val saveResult = mediaStoreManager.saveImage(
                    bitmap = captureResult.bitmap,
                    metadata = metadata,
                    analysis = analysis
                )
                android.util.Log.d("Capture", "Step 6: Saved to MediaStore: ${saveResult.imagePath}")

                // Step 7: Save to local database
                val safeLocationResult = locationResult ?: HighAccuracyLocationManager.LocationResult(
                    location = null,
                    accuracy = Float.MAX_VALUE,
                    accuracyRating = HighAccuracyLocationManager.AccuracyRating.UNACCEPTABLE,
                    isFresh = false,
                    provider = "none"
                )
                
                try {
                    withContext(Dispatchers.IO) {
                        saveImageToDb(saveResult.imagePath, captureResult, analysis, safeLocationResult)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("Capture", "Database save failed", e)
                    _uiState.value = _uiState.value.copy(
                        statusMessage = "⚠️ Saved to gallery but DB failed: ${e.message}"
                    )
                }

                // Step 8: Update UI
                android.util.Log.d("Capture", "Step 8: Updating UI...")
                val locationStatus = when {
                    locationResult?.location != null -> 
                        "Saved with GPS (${locationResult.accuracy.toInt()}m accuracy)"
                    else -> "Saved (no GPS - indoors)"
                }
                
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    lastCaptureFlags = analysis.flags,
                    showFlagPrompt = analysis.flags.isNotEmpty(),
                    lastCapturePath = saveResult.imagePath,
                    captureCount = _uiState.value.captureCount + 1,
                    lastAnalysis = analysis,
                    statusMessage = locationStatus,
                    lastGpsAccuracy = locationResult?.accuracyRating
                )
                android.util.Log.d("Capture", "=== Capture complete ===")

            } catch (e: Exception) {
                android.util.Log.e("Capture", "Capture failed", e)
                _uiState.value = _uiState.value.copy(
                    isCapturing = false,
                    statusMessage = "Capture failed: ${e.message}"
                )
            } finally {
                // Step 9: Cleanup - always recycle bitmap
                android.util.Log.d("Capture", "Step 9: Cleanup...")
                capturedBitmap?.recycle()
            }
        }
    }

    private fun lockFromFirstShot(luminance: Float, colorTemp: Float) {
        android.util.Log.d("Capture", "Locking session with luminance=$luminance, colorTemp=$colorTemp")
        sessionManager.lockSession(
            iso = 0,
            shutterSpeedNs = 0L,
            whiteBalanceKelvin = 0,
            exposureCompensation = 0,
            baselineLuminance = luminance,
            baselineColorTemp = colorTemp,
            locationLabel = sessionManager.settings.value.locationLabel
        )
        // Don't update UI state here - let the caller handle it to avoid overwriting isCapturing
        android.util.Log.d("Capture", "Session locked successfully")
    }

    private suspend fun saveImageToDb(
        imagePath: String,
        capture: EnhancedCameraController.CaptureResult,
        analysis: EnhancedFrameAnalyzer.EnhancedAnalysisResult,
        locationResult: HighAccuracyLocationManager.LocationResult
    ) {
        try {
            val thumbPath = saveThumbnail(capture.bitmap, capture.file)
            val s = sessionManager.settings.value

            android.util.Log.d("Capture", "Creating location for image...")
            val locationSaveResult = locationMemory.resolveOrCreateLocation(
                thumbnailPath = thumbPath,
                settings = s,
                highAccuracyResult = locationResult
            )

            val locationId = when (locationSaveResult) {
                is LocationMemory.LocationSaveResult.WithGps -> {
                    _uiState.value = _uiState.value.copy(activeLocationId = locationSaveResult.locationId)
                    locationSaveResult.locationId
                }
                is LocationMemory.LocationSaveResult.WithoutGps -> {
                    _uiState.value = _uiState.value.copy(
                        activeLocationId = locationSaveResult.locationId,
                        statusMessage = "⚠️ No GPS — saved to unassigned location"
                    )
                    locationSaveResult.locationId
                }
            }

            android.util.Log.d("Capture", "Saving image to DB with locationId=$locationId")
            val imageId = locationMemory.saveImage(ShootImage(
                locationId = locationId,
                imagePath = imagePath,
                thumbnailPath = thumbPath,
                capturedAt = capture.timestamp,
                blurScore = analysis.blurScore,
                luminance = analysis.luminance,
                colorTempK = analysis.estimatedColorTempK,
                hadFlags = analysis.flags.isNotEmpty(),
                flagTypes = analysis.flags.joinToString(",") { it.type.name },
                iso = s.iso,
                shutterNs = s.shutterSpeedNs,
                whiteBalanceK = s.whiteBalanceKelvin
            ))
            android.util.Log.d("Capture", "Image saved to DB with id=$imageId")
        } catch (e: Exception) {
            android.util.Log.e("Capture", "Failed to save image to database", e)
            throw e // Re-throw so caller can handle it
        }
    }

    private fun saveThumbnail(bitmap: Bitmap, originalFile: File): String {
        val thumbDir = File(getApplication<Application>().filesDir, "thumbnails")
        if (!thumbDir.exists()) thumbDir.mkdirs()
        val thumbFile = File(thumbDir, "${originalFile.nameWithoutExtension}_thumb.jpg")

        // Apply EXIF orientation from original capture so thumbnail is upright
        val orientedBitmap = ImageLoader.loadBitmap(originalFile) ?: bitmap
        val scaled = Bitmap.createScaledBitmap(orientedBitmap, 200, 150, true)
        try {
            FileOutputStream(thumbFile).use { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } finally {
            if (orientedBitmap !== bitmap) {
                MemoryManager.recycle(orientedBitmap, "orientedThumb")
            }
            scaled.recycle()
        }
        return thumbFile.absolutePath
    }

    // ─── Session Management ───────────────────────────────────────

    fun startNewSession() {
        currentSessionId = generateSessionId()
        sessionManager.unlockSession()
        _uiState.value = _uiState.value.copy(
            captureCount = 0,
            statusMessage = "New session started",
            lastCaptureFlags = emptyList(),
            showFlagPrompt = false
        )
    }

    private fun generateSessionId(): String {
        return "sess_${System.currentTimeMillis()}_${Random().nextInt(10000)}"
    }

    // ─── Overlay ──────────────────────────────────────────────────

    fun loadOverlayImages(locationId: Long) {
        viewModelScope.launch {
            val images = locationMemory.getPastImagesForLocation(locationId)
            _uiState.value = _uiState.value.copy(overlayImages = images)
        }
    }

    fun selectOverlayImage(imagePath: String) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                ImageLoader.loadBitmap(getApplication(), imagePath)
            }
            _uiState.value.activeOverlayBitmap?.recycle()
            _uiState.value = _uiState.value.copy(
                activeOverlayBitmap = bitmap,
                overlayOpacity = 0.35f
            )
        }
    }

    fun setOverlayOpacity(opacity: Float) {
        _uiState.value = _uiState.value.copy(overlayOpacity = opacity)
    }

    fun clearOverlay() {
        _uiState.value.activeOverlayBitmap?.recycle()
        _uiState.value = _uiState.value.copy(activeOverlayBitmap = null)
    }

    // ─── Flags ────────────────────────────────────────────────────

    fun dismissFlags() {
        sessionManager.clearFlags()
        _uiState.value = _uiState.value.copy(
            showFlagPrompt = false,
            lastCaptureFlags = emptyList()
        )
    }

    fun requestApiReview() {
        _uiState.value = _uiState.value.copy(
            showFlagPrompt = false,
            statusMessage = "API review queued (coming in v2)"
        )
    }

    fun unlockSession() {
        sessionManager.unlockSession()
        _uiState.value = _uiState.value.copy(
            statusMessage = "🔓 Session unlocked — back to auto",
            showResetConfirm = false,
            isCapturing = false // Reset capture state in case it got stuck
        )
    }
    
    /**
     * Emergency reset - use if capture gets stuck
     */
    fun emergencyReset() {
        android.util.Log.w("Capture", "Emergency reset called!")
        _uiState.value = _uiState.value.copy(
            isCapturing = false,
            statusMessage = "Reset - ready to capture"
        )
    }

    fun showResetConfirmation() {
        _uiState.value = _uiState.value.copy(showResetConfirm = true)
    }

    fun hideResetConfirmation() {
        _uiState.value = _uiState.value.copy(showResetConfirm = false)
    }

    // ─── Utilities ────────────────────────────────────────────────

    private fun getDeviceRotation(): Int {
        val windowManager = getApplication<Application>().getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getApplication<Application>().display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun getOutputDir(): File {
        val dir = File(getApplication<Application>().filesDir, "captures")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun onCleared() {
        super.onCleared()
        _uiState.value.activeOverlayBitmap?.recycle()
        cameraController.shutdown()
    }
}

data class EnhancedCameraUiState(
    val isCapturing: Boolean = false,
    val captureCount: Int = 0,
    val statusMessage: String = "",
    val lastCapturePath: String = "",
    val lastCaptureFlags: List<com.pixelhunter.cam.session.SessionFlag> = emptyList(),
    val showFlagPrompt: Boolean = false,
    val showResetConfirm: Boolean = false,
    val nearbyLocations: List<LocationMemory.NearbyResult> = emptyList(),
    val showLocationPrompt: Boolean = false,
    val activeLocationId: Long? = null,
    val overlayImages: List<ShootImage> = emptyList(),
    val activeOverlayBitmap: Bitmap? = null,
    val overlayOpacity: Float = 0.35f,
    val lastAnalysis: EnhancedFrameAnalyzer.EnhancedAnalysisResult? = null,
    val lastGpsAccuracy: HighAccuracyLocationManager.AccuracyRating? = null
)
