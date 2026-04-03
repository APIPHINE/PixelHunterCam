package com.pixelhunter.cam.util

import android.content.Context
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pixelhunter.cam.util.ErrorHandler.ErrorCategory
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.ExecutorService
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Safe CameraX operations with comprehensive error handling.
 * 
 * Wraps camera operations to prevent crashes from:
 * - Camera hardware failures
 * - Permission issues
 * - Binding conflicts
 * - Capture timeouts
 */
object SafeCamera {
    
    private const val TAG = "SafeCamera"
    private const val CAPTURE_TIMEOUT_MS = 10000L // 10 seconds
    
    data class CameraSetupResult(
        val success: Boolean,
        val camera: Camera? = null,
        val imageCapture: ImageCapture? = null,
        val preview: Preview? = null,
        val error: String? = null
    )
    
    data class CaptureResult(
        val success: Boolean,
        val file: File? = null,
        val error: String? = null
    )
    
    /**
     * Safely initialize and bind camera with retry logic.
     */
    suspend fun setupCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA,
        setupBlock: (ImageCapture.Builder, Preview.Builder) -> Unit = { _, _ -> }
    ): CameraSetupResult {
        return ErrorHandler.executeSafe(
            operationName = "setupCamera",
            category = ErrorCategory.CAMERA,
            maxAttempts = 3
        ) {
            val cameraProvider = getCameraProvider(context)
            
            // Unbind any existing use cases
            cameraProvider.unbindAll()
            
            // Build use cases
            val previewBuilder = Preview.Builder()
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            
            // Apply custom setup
            setupBlock(captureBuilder, previewBuilder)
            
            val preview = previewBuilder.build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            
            val imageCapture = captureBuilder.build()
            
            // Bind to lifecycle
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            
            Log.i(TAG, "✅ Camera setup successful")
            Triple(camera, imageCapture, preview)
        }.let { result ->
            CameraSetupResult(
                success = result.success,
                camera = result.data?.first,
                imageCapture = result.data?.second,
                preview = result.data?.third,
                error = result.errorMessage.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    /**
     * Safely capture an image with timeout and retry.
     */
    suspend fun captureImage(
        imageCapture: ImageCapture,
        outputFile: File,
        executor: ExecutorService,
        onProgress: (CaptureProgress) -> Unit = {}
    ): CaptureResult {
        return ErrorHandler.executeSafe(
            operationName = "captureImage",
            category = ErrorCategory.CAMERA,
            maxAttempts = 2
        ) {
            onProgress(CaptureProgress.STARTED)
            
            val result = suspendCancellableCoroutine<File> { continuation ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                
                imageCapture.takePicture(
                    outputOptions,
                    executor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            onProgress(CaptureProgress.SAVED)
                            continuation.resume(outputFile)
                        }
                        
                        override fun onError(exception: ImageCaptureException) {
                            Log.e(TAG, "Capture error: ${exception.message}", exception)
                            continuation.resumeWithException(exception)
                        }
                    }
                )
                
                // Timeout handling
                continuation.invokeOnCancellation {
                    Log.w(TAG, "Capture cancelled")
                }
            }
            
            onProgress(CaptureProgress.COMPLETED)
            result
        }.let { result ->
            CaptureResult(
                success = result.success,
                file = result.data,
                error = result.errorMessage.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    /**
     * Safely switch between cameras with error recovery.
     */
    suspend fun switchCamera(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        currentCameraIndex: Int,
        availableCameras: List<CameraSelector>,
        setupBlock: (ImageCapture.Builder, Preview.Builder) -> Unit = { _, _ -> }
    ): CameraSetupResult {
        if (availableCameras.isEmpty()) {
            return CameraSetupResult(false, error = "No cameras available")
        }
        
        val nextIndex = (currentCameraIndex + 1) % availableCameras.size
        val nextSelector = availableCameras[nextIndex]
        
        return setupCamera(context, lifecycleOwner, previewView, nextSelector, setupBlock)
    }
    
    /**
     * Safely set zoom level.
     */
    fun setZoom(camera: Camera?, zoomRatio: Float): Boolean {
        return try {
            camera?.cameraControl?.setZoomRatio(zoomRatio.coerceIn(1.0f, getMaxZoom(camera)))
            Log.d(TAG, "🔍 Zoom set to ${zoomRatio}x")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to set zoom: ${e.message}")
            false
        }
    }
    
    /**
     * Get maximum zoom ratio for a camera.
     */
    fun getMaxZoom(camera: Camera?): Float {
        return try {
            camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1.0f
        } catch (e: Exception) {
            1.0f
        }
    }
    
    /**
     * Safely start tap-to-focus.
     */
    fun startFocus(
        camera: Camera?,
        focusPoint: androidx.camera.core.FocusMeteringAction,
        onResult: (Boolean) -> Unit = {}
    ): Boolean {
        return try {
            camera?.cameraControl?.startFocusAndMetering(focusPoint)
                ?.addListener({
                    onResult(true)
                }, ContextCompat.getMainExecutor(camera!!.cameraControl.javaClass.classLoader as Context))
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Focus failed: ${e.message}")
            onResult(false)
            false
        }
    }
    
    /**
     * Check if camera is available (not in use by another app).
     */
    suspend fun isCameraAvailable(context: Context): Boolean {
        return try {
            val provider = getCameraProvider(context)
            provider.availableCameraInfos.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Camera availability check failed: ${e.message}")
            false
        }
    }
    
    /**
     * Get list of available camera selectors.
     */
    suspend fun getAvailableCameras(context: Context): List<CameraSelector> {
        return try {
            val provider = getCameraProvider(context)
            provider.availableCameraInfos.map { CameraSelector.DEFAULT_BACK_CAMERA }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cameras: ${e.message}")
            emptyList()
        }
    }
    
    /**
     * Unbind camera safely.
     */
    suspend fun unbindCamera(context: Context) {
        try {
            val provider = getCameraProvider(context)
            provider.unbindAll()
            Log.d(TAG, "✂️ Camera unbound")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Error unbinding camera: ${e.message}")
        }
    }
    
    /**
     * Emergency camera reset - use when camera is in bad state.
     */
    suspend fun emergencyReset(context: Context): Boolean {
        return ErrorHandler.executeSafe(
            operationName = "emergencyCameraReset",
            category = ErrorCategory.CAMERA
        ) {
            // Force unbind
            val provider = getCameraProvider(context)
            provider.unbindAll()
            
            // Small delay for hardware to settle
            kotlinx.coroutines.delay(500)
            
            true
        }.success
    }
    
    /**
     * Get camera provider with caching.
     */
    private suspend fun getCameraProvider(context: Context): ProcessCameraProvider {
        return suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }, ContextCompat.getMainExecutor(context))
        }
    }
    
    enum class CaptureProgress {
        STARTED,
        SAVED,
        COMPLETED,
        FAILED
    }
    
    /**
     * Validate camera setup before use.
     */
    fun validateCameraSetup(
        context: Context,
        imageCapture: ImageCapture?,
        preview: Preview?
    ): ValidationResult {
        val issues = mutableListOf<String>()
        
        if (imageCapture == null) {
            issues.add("ImageCapture not initialized")
        }
        
        if (preview == null) {
            issues.add("Preview not initialized")
        }
        
        // Check permissions
        val hasCameraPermission = context.checkSelfPermission(
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        
        if (!hasCameraPermission) {
            issues.add("Camera permission not granted")
        }
        
        return ValidationResult(issues.isEmpty(), issues)
    }
    
    data class ValidationResult(
        val isValid: Boolean,
        val issues: List<String>
    )
}

/**
 * Extension to safely handle camera results.
 */
inline fun SafeCamera.CaptureResult.onSuccess(action: (File) -> Unit): SafeCamera.CaptureResult {
    if (success) file?.let(action)
    return this
}

inline fun SafeCamera.CaptureResult.onFailure(action: (String) -> Unit): SafeCamera.CaptureResult {
    if (!success) error?.let(action)
    return this
}
