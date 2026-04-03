package com.pixelhunter.cam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pixelhunter.cam.session.SessionManager
import com.pixelhunter.cam.session.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Enhanced Camera Controller with:
 * - Tap to focus
 * - Pinch to zoom
 * - Real-time camera metadata (ISO, shutter, EV)
 * - Multiple lens support (wide, ultrawide, telephoto)
 * - Focus peaking support
 * - Better exposure handling
 */
@OptIn(ExperimentalCamera2Interop::class)
class EnhancedCameraController(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "EnhancedCameraController"
        private const val FOCUS_AREA_SIZE = 150
        private val kelvinGainCache = HashMap<Int, RggbChannelVector>(64)
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // Camera metadata tracking
    private val _cameraState = MutableStateFlow(CameraState())
    val cameraState: StateFlow<CameraState> = _cameraState

    // Available cameras
    private var availableCameras: List<CameraId> = emptyList()
    private var currentCameraIndex = 0

    // Zoom
    private var zoomState: ZoomState? = null
    private var currentZoom = 1.0f

    // Focus tracking
    private var isFocusing = false

    data class CameraId(val cameraId: String, val lensFacing: Int, val focalLength: Float?)
    
    data class CameraState(
        val iso: Int = 0,
        val shutterSpeedNs: Long = 0L,
        val exposureCompensation: Int = 0,
        val afState: Int = 0,
        val aeState: Int = 0,
        val awbState: Int = 0,
        val currentZoom: Float = 1.0f,
        val maxZoom: Float = 1.0f,
        val focusDistance: Float = 0f,
        val isLensAvailable: Boolean = false,
        val activeCameraName: String = "Standard"
    )

    // ─── Camera Setup ─────────────────────────────────────────────

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onMetadataUpdate: ((CameraState) -> Unit)? = null
    ) {
        val provider = getCameraProvider()
        cameraProvider = provider

        // Discover available cameras
        discoverCameras()

        bindCameraUseCases(lifecycleOwner, previewView)
        
        // Start metadata monitoring
        onMetadataUpdate?.let { startMetadataMonitoring(it) }
    }

    private fun discoverCameras() {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        availableCameras = try {
            cameraManager.cameraIdList.mapNotNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING) ?: return@mapNotNull null
                val focalLength = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
                CameraId(id, lensFacing, focalLength)
            }.filter { it.lensFacing == CameraCharacteristics.LENS_FACING_BACK }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discover cameras", e)
            emptyList()
        }
        
        Log.d(TAG, "Found ${availableCameras.size} cameras: ${availableCameras.map { "${it.cameraId} (${it.focalLength}mm)" }}")
    }

    private fun bindCameraUseCases(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val provider = cameraProvider ?: return
        val settings = sessionManager.settings.value

        provider.unbindAll()

        // Select camera
        val cameraSelector = if (availableCameras.isNotEmpty() && currentCameraIndex < availableCameras.size) {
            val cameraId = availableCameras[currentCameraIndex].cameraId
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == cameraId }
                }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        // Preview
        val previewBuilder = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)

        // Image capture
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)

        // Apply manual settings
        if (settings.isLocked) {
            applyManualSettings(previewBuilder, captureBuilder, settings)
        }

        preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = captureBuilder.build()

        try {
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            // Setup zoom
            setupZoom()
            
            // Update camera name
            updateActiveCameraName()
            
            Log.d(TAG, "Camera bound. Locked: ${settings.isLocked}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    // ─── Multi-Lens Support ───────────────────────────────────────

    fun switchLens(): Boolean {
        if (availableCameras.size <= 1) return false
        currentCameraIndex = (currentCameraIndex + 1) % availableCameras.size
        return true
    }

    fun getAvailableLensNames(): List<String> = availableCameras.map { cameraId ->
        when {
            cameraId.focalLength == null -> "Standard"
            cameraId.focalLength < 20 -> "Ultra Wide (${cameraId.focalLength.toInt()}mm)"
            cameraId.focalLength < 35 -> "Wide (${cameraId.focalLength.toInt()}mm)"
            cameraId.focalLength < 80 -> "Standard (${cameraId.focalLength.toInt()}mm)"
            else -> "Telephoto (${cameraId.focalLength.toInt()}mm)"
        }
    }

    private fun updateActiveCameraName() {
        val names = getAvailableLensNames()
        if (currentCameraIndex < names.size) {
            _cameraState.value = _cameraState.value.copy(
                activeCameraName = names[currentCameraIndex],
                isLensAvailable = availableCameras.size > 1
            )
        }
    }

    // ─── Zoom Control ─────────────────────────────────────────────

    private fun setupZoom() {
        val camera = this.camera ?: return
        val cameraControl = camera.cameraControl
        val cameraInfo = camera.cameraInfo

        cameraInfo.zoomState.observeForever { state ->
            zoomState = state
            _cameraState.value = _cameraState.value.copy(
                maxZoom = state?.maxZoomRatio ?: 1.0f,
                currentZoom = state?.zoomRatio ?: 1.0f
            )
        }
    }

    fun setZoom(zoomRatio: Float) {
        val camera = this.camera ?: return
        val clampedZoom = zoomRatio.coerceIn(1.0f, zoomState?.maxZoomRatio ?: 1.0f)
        currentZoom = clampedZoom
        camera.cameraControl.setZoomRatio(clampedZoom)
    }

    fun getZoomRatio(): Float = currentZoom
    fun getMaxZoom(): Float = zoomState?.maxZoomRatio ?: 1.0f

    // ─── Tap to Focus ─────────────────────────────────────────────

    fun handleTapToFocus(event: MotionEvent, previewView: PreviewView) {
        val camera = this.camera ?: return
        if (isFocusing) return

        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(event.x, event.y, FOCUS_AREA_SIZE.toFloat())
        
        val action = FocusMeteringAction.Builder(point)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        isFocusing = true
        camera.cameraControl.startFocusAndMetering(action)
            .addListener({
                isFocusing = false
            }, ContextCompat.getMainExecutor(context))

        Log.d(TAG, "Focus triggered at (${event.x}, ${event.y})")
    }

    // ─── Real-time Metadata Monitoring ────────────────────────────

    private fun startMetadataMonitoring(onUpdate: (CameraState) -> Unit) {
        val camera = this.camera ?: return
        val camera2CameraControl = Camera2CameraControl.from(camera.cameraControl)
        val camera2CameraInfo = Camera2CameraInfo.from(camera.cameraInfo)

        // Monitor capture results - simplified approach
        // For real-time metadata, we'd need a custom UseCase with CameraCaptureCallback
        // This is a placeholder for future enhancement

        // Use CameraControl's setZoomRatio to trigger metadata updates
        mainHandler.post(object : Runnable {
            override fun run() {
                updateCameraStateFromSession()
                onUpdate(_cameraState.value)
                mainHandler.postDelayed(this, 500)
            }
        })
    }

    private fun updateCameraStateFromSession() {
        val settings = sessionManager.settings.value
        _cameraState.value = _cameraState.value.copy(
            iso = settings.iso,
            shutterSpeedNs = settings.shutterSpeedNs,
            exposureCompensation = settings.exposureCompensation,
            currentZoom = currentZoom
        )
    }

    // ─── Manual Settings ──────────────────────────────────────────

    private fun applyManualSettings(
        previewBuilder: Preview.Builder,
        captureBuilder: ImageCapture.Builder,
        settings: SessionSettings
    ) {
        val previewEx = Camera2Interop.Extender(previewBuilder)
        val captureEx = Camera2Interop.Extender(captureBuilder)

        // ISO + Shutter
        if (settings.iso > 0 || settings.shutterSpeedNs > 0L) {
            val iso = if (settings.iso > 0) settings.iso else 100
            val shutter = if (settings.shutterSpeedNs > 0L) settings.shutterSpeedNs
                          else 1_000_000_000L / 60L
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_OFF)
                it.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                it.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            }
            Log.d(TAG, "AE locked: ISO $iso, ${shutter}ns")
        }

        // White Balance
        if (settings.whiteBalanceKelvin > 0) {
            val gains = kelvinToRggbGains(settings.whiteBalanceKelvin)
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF)
                it.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                it.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            }
            Log.d(TAG, "WB locked: ${settings.whiteBalanceKelvin}K")
        }

        // Exposure compensation (only when AE is on)
        if (settings.exposureCompensation != 0 && settings.iso == 0) {
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    settings.exposureCompensation)
            }
        }

        // Focus
        if (settings.focusDistanceDiopters > 0f) {
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF)
                it.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistanceDiopters)
            }
        }
    }

    // ─── Kelvin to RGGB Gains ─────────────────────────────────────

    fun kelvinToRggbGains(kelvin: Int): RggbChannelVector {
        val quantized = (kelvin / 100) * 100
        kelvinGainCache[quantized]?.let { return it }
        val k = quantized.coerceIn(2000, 10000).toFloat()
        val rGain = when {
            k <= 4000f -> 1.0f + (4000f - k) / 4000f * 0.8f
            k <= 6500f -> 1.0f
            else       -> 1.0f - (k - 6500f) / 7000f * 0.35f
        }.coerceIn(0.5f, 2.5f)
        val bGain = when {
            k <= 4000f -> 1.0f - (4000f - k) / 4000f * 0.45f
            k <= 6500f -> 1.0f
            else       -> 1.0f + (k - 6500f) / 7000f * 0.7f
        }.coerceIn(0.5f, 2.5f)
        return RggbChannelVector(rGain, 1.0f, 1.0f, bGain).also { kelvinGainCache[quantized] = it }
    }

    // ─── Flash Control ────────────────────────────────────────────

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun getFlashMode(): Int = imageCapture?.flashMode ?: ImageCapture.FLASH_MODE_AUTO

    // ─── Capture ──────────────────────────────────────────────────

    data class CaptureResult(val file: File, val bitmap: Bitmap, val timestamp: Long, val metadata: CaptureMetadata)
    
    data class CaptureMetadata(
        // What we requested
        val iso: Int,
        val shutterSpeedNs: Long,
        val focalLength: Float?,
        val aperture: Float?,
        // What the sensor actually used — populated via Camera2 callback when available
        val actualIso: Int = iso,
        val actualShutterNs: Long = shutterSpeedNs,
        val actualAeModeOff: Boolean = false,
        val actualAwbModeOff: Boolean = false
    ) {
        val settingsVerified: Boolean get() = actualAeModeOff && actualAwbModeOff
    }

    suspend fun capturePhoto(outputDir: File): CaptureResult {
        val file = takePictureToFile(outputDir)
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Decode failed: ${file.name}")
        }
        
        val metadata = CaptureMetadata(
            iso = _cameraState.value.iso,
            shutterSpeedNs = _cameraState.value.shutterSpeedNs,
            focalLength = availableCameras.getOrNull(currentCameraIndex)?.focalLength,
            aperture = null // Would need Camera2 CaptureCallback for this
        )
        
        return CaptureResult(file, bitmap, System.currentTimeMillis(), metadata)
    }

    private suspend fun takePictureToFile(outputDir: File): File = suspendCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not started"))
            return@suspendCoroutine
        }
        val fileName = "PHC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        val outputFile = File(outputDir, fileName)
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outputFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = cont.resume(outputFile)
                override fun onError(e: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", e)
                    cont.resumeWithException(e)
                }
            }
        )
    }

    // ─── Rebind (for lens switching) ──────────────────────────────

    fun rebindCamera(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        bindCameraUseCases(lifecycleOwner, previewView)
    }

    // ─── Cleanup ──────────────────────────────────────────────────

    fun shutdown() {
        mainHandler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
    }
}
