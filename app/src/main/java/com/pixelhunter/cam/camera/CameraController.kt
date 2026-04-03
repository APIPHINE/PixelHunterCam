package com.pixelhunter.cam.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.pixelhunter.cam.session.SessionManager
import com.pixelhunter.cam.session.SessionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class CameraController(
    private val context: Context,
    private val sessionManager: SessionManager
) {
    companion object {
        private const val TAG = "CameraController"
        // Cache Kelvin→RGGB gains. Values are discrete (100K steps) so we memoize
        // to avoid recomputing the piecewise polynomial on every settings apply.
        private val kelvinGainCache = HashMap<Int, RggbChannelVector>(64)
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ─── Start camera ─────────────────────────────────────────────
    //
    // IMPORTANT: Camera2Interop.Extender mutates its builder via the builder's
    // internal tag/options map. The extender does NOT return a new builder — it
    // modifies the existing one in-place. As long as we call .build() on the SAME
    // builder instance that was passed to the extender, all options are included.
    // Do not reassign previewBuilder or captureBuilder after passing them to
    // applyManualSettings.

    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView
    ) {
        val provider = getCameraProvider()
        cameraProvider = provider

        val settings = sessionManager.settings.value

        val previewBuilder = Preview.Builder()
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)

        // applyManualSettings mutates the builders via Camera2Interop.Extender.
        // .build() is called below, AFTER mutation is complete.
        if (settings.isLocked) {
            applyManualSettings(previewBuilder, captureBuilder, settings)
        }

        val preview = previewBuilder.build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = captureBuilder.build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            Log.d(TAG, "Camera started. Locked: ${settings.isLocked}")
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
        }
    }

    // ─── Apply manual settings ────────────────────────────────────

    private fun applyManualSettings(
        previewBuilder: Preview.Builder,
        captureBuilder: ImageCapture.Builder,
        settings: SessionSettings
    ) {
        val previewEx = Camera2Interop.Extender(previewBuilder)
        val captureEx = Camera2Interop.Extender(captureBuilder)

        // ISO + Shutter — must set both when disabling AE
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

        // White balance — convert Kelvin to actual RGGB channel gains
        if (settings.whiteBalanceKelvin > 0) {
            val gains = kelvinToRggbGains(settings.whiteBalanceKelvin)
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE,
                    CaptureRequest.CONTROL_AWB_MODE_OFF)
                it.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE,
                    CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                it.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
            }
            Log.d(TAG, "WB locked: ${settings.whiteBalanceKelvin}K → R=${gains.red} B=${gains.blue}")
        }

        // Exposure compensation — only meaningful when AE is still active
        if (settings.exposureCompensation != 0 && settings.iso == 0) {
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                    settings.exposureCompensation)
            }
        }

        // Focus — diopters (1/metres); 0 = auto
        if (settings.focusDistanceDiopters > 0f) {
            listOf(previewEx, captureEx).forEach {
                it.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_OFF)
                it.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE,
                    settings.focusDistanceDiopters)
            }
            Log.d(TAG, "Focus locked: ${settings.focusDistanceDiopters}D " +
                    "(~${"%.2f".format(1f / settings.focusDistanceDiopters)}m)")
        }
    }

    // ─── Kelvin → RGGB gains ──────────────────────────────────────
    // Piecewise approximation of the Planckian locus. Green = 1.0 (reference).
    // Range: 2000–10000K. Results cached per 100K quantum.

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

    // ─── Capture photo ────────────────────────────────────────────
    // File write happens on cameraExecutor.
    // Bitmap decode moved to Dispatchers.Default — a full-res Pixel image
    // is 8–15MB; decoding on the wrong thread causes jank or ANR.

    data class CaptureResult(val file: File, val bitmap: Bitmap, val timestamp: Long)

    suspend fun capturePhoto(outputDir: File): CaptureResult {
        val file = takePictureToFile(outputDir)
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeFile(file.absolutePath)
                ?: throw IllegalStateException("Decode failed: ${file.name}")
        }
        return CaptureResult(file, bitmap, System.currentTimeMillis())
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

    private suspend fun getCameraProvider(): ProcessCameraProvider = suspendCoroutine { cont ->
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
        }
    }

    fun shutdown() { cameraExecutor.shutdown() }
}
