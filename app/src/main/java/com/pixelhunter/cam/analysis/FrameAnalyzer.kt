package com.pixelhunter.cam.analysis

import android.graphics.Bitmap
import android.graphics.Color
import androidx.annotation.WorkerThread
import com.pixelhunter.cam.session.FlagType
import com.pixelhunter.cam.session.SessionFlag
import com.pixelhunter.cam.session.SessionManager
import com.pixelhunter.cam.session.Severity
import kotlin.math.pow

/**
 * All frame analysis runs locally on-device.
 * No API calls here — just math on pixel data.
 *
 * Analyses:
 *  1. Laplacian variance blur detection (global + tiled)
 *  2. Perceptual luminance (sRGB gamma-corrected)
 *  3. Estimated colour temperature (R/B ratio proxy)
 *
 * All public methods are @WorkerThread — never call from main thread.
 * CameraController decodes bitmaps on Dispatchers.Default; FrameAnalyzer
 * is called from there, satisfying this requirement.
 */
@WorkerThread
object FrameAnalyzer {

    private const val TILE_COLS = 4
    private const val TILE_ROWS = 4

    data class AnalysisResult(
        val blurScore: Double,
        val luminance: Float,           // 0.0–1.0, perceptual (gamma-corrected)
        val estimatedColorTempK: Float,
        val isGloballyBlurry: Boolean,
        val isLocallyBlurry: Boolean,
        val blurryTileRatio: Float,
        val flags: List<SessionFlag>
    )

    fun analyze(bitmap: Bitmap, sessionManager: SessionManager): AnalysisResult {
        val flags = mutableListOf<SessionFlag>()

        // Scale down for speed. try/finally ensures scaled is always recycled
        // even if an exception is thrown mid-analysis.
        val scaled = scaleBitmap(bitmap, 320, 240)
        try {
            val blurScore = computeLaplacianVariance(scaled)
            val luminance = computeLuminancePerceptual(scaled)
            val colorTemp = estimateColorTemperature(scaled)
            val tileBlurinessRatio = computeTileBlur(scaled)

            val isGlobalBlurry = blurScore < SessionManager.BLUR_GLOBAL_THRESHOLD
            val isLocalBlurry = !isGlobalBlurry &&
                    tileBlurinessRatio > SessionManager.BLUR_TILE_FAIL_RATIO

            if (isGlobalBlurry) {
                flags += SessionFlag(
                    type = FlagType.BLUR_GLOBAL,
                    severity = if (blurScore < 40) Severity.HIGH else Severity.MEDIUM,
                    message = "Camera shake detected — motion blur across entire frame",
                    suggestApiReview = true
                )
            } else if (isLocalBlurry) {
                flags += SessionFlag(
                    type = FlagType.BLUR_LOCAL,
                    severity = Severity.LOW,
                    message = "${(tileBlurinessRatio * 100).toInt()}% of frame blurry — subject motion?",
                    suggestApiReview = false
                )
            }

            sessionManager.checkDrift(luminance, colorTemp)

            // Pick up any drift flags the session manager just set
            flags += sessionManager.flags.value.filter {
                it.type == FlagType.EXPOSURE_DRIFT || it.type == FlagType.WHITE_BALANCE_DRIFT
            }

            return AnalysisResult(blurScore, luminance, colorTemp,
                isGlobalBlurry, isLocalBlurry, tileBlurinessRatio, flags)

        } finally {
            scaled.recycle()
        }
    }

    // ─── Laplacian variance ────────────────────────────────────────

    private fun computeLaplacianVariance(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val values = DoubleArray((w - 2) * (h - 2))
        var i = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val lap = 4 * linearLuminance(pixels[y * w + x]) -
                        linearLuminance(pixels[(y - 1) * w + x]) -
                        linearLuminance(pixels[(y + 1) * w + x]) -
                        linearLuminance(pixels[y * w + (x - 1)]) -
                        linearLuminance(pixels[y * w + (x + 1)])
                values[i++] = lap
            }
        }
        return varianceArray(values, i)
    }

    // ─── Tiled blur detection ──────────────────────────────────────
    // Operates on the parent pixel array directly — no sub-bitmap allocations.

    private fun computeTileBlur(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val tileW = w / TILE_COLS
        val tileH = h / TILE_ROWS
        var blurryCount = 0
        for (row in 0 until TILE_ROWS) {
            for (col in 0 until TILE_COLS) {
                val score = laplacianVarianceInRegion(
                    pixels, w, col * tileW, row * tileH, tileW, tileH)
                if (score < SessionManager.BLUR_LOCAL_THRESHOLD) blurryCount++
            }
        }
        return blurryCount.toFloat() / (TILE_COLS * TILE_ROWS)
    }

    private fun laplacianVarianceInRegion(
        pixels: IntArray, stride: Int,
        startX: Int, startY: Int, regionW: Int, regionH: Int
    ): Double {
        val values = DoubleArray((regionW - 2) * (regionH - 2))
        var i = 0
        for (y in (startY + 1) until (startY + regionH - 1)) {
            for (x in (startX + 1) until (startX + regionW - 1)) {
                val lap = 4 * linearLuminance(pixels[y * stride + x]) -
                        linearLuminance(pixels[(y - 1) * stride + x]) -
                        linearLuminance(pixels[(y + 1) * stride + x]) -
                        linearLuminance(pixels[y * stride + (x - 1)]) -
                        linearLuminance(pixels[y * stride + (x + 1)])
                values[i++] = lap
            }
        }
        return varianceArray(values, i)
    }

    // ─── Perceptual luminance ──────────────────────────────────────
    // sRGB gamma-corrected average luminance (Rec. 709 weights).
    // Using linear-space average would overweight dark areas and
    // underweight bright areas relative to human perception.

    fun computeLuminancePerceptual(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var sum = 0.0
        for (pixel in pixels) {
            // Convert to linear light, apply Rec. 709 coefficients, then gamma-encode
            val r = srgbToLinear(Color.red(pixel) / 255f)
            val g = srgbToLinear(Color.green(pixel) / 255f)
            val b = srgbToLinear(Color.blue(pixel) / 255f)
            val linear = 0.2126 * r + 0.7152 * g + 0.0722 * b
            // Re-encode to perceptual (gamma ~2.2) for a value matching human perceived brightness
            sum += linear.pow(1.0 / 2.2)
        }
        return (sum / pixels.size).toFloat().coerceIn(0f, 1f)
    }

    // ─── Colour temperature estimation ────────────────────────────
    // R/B ratio proxy. Not scientifically precise, but reliable enough
    // for session drift detection. Not suitable for absolute colour science.
    // Limitations: fails under strongly coloured non-white lighting (e.g.
    // stage gels). Consider flagging "non-neutral scene" in a future version.

    fun estimateColorTemperature(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var totalR = 0L
        var totalB = 0L
        for (pixel in pixels) {
            totalR += Color.red(pixel)
            totalB += Color.blue(pixel)
        }
        val avgR = totalR.toFloat() / pixels.size
        val avgB = totalB.toFloat() / pixels.size
        if (avgB < 1f) return 6500f
        val ratio = avgR / avgB
        return when {
            ratio >= 1.5f -> 3000f
            ratio <= 0.7f -> 8000f
            else -> 8000f - ((ratio - 0.7f) / (1.5f - 0.7f)) * 5000f
        }
    }

    // ─── Utilities ────────────────────────────────────────────────

    /** Linear (non-gamma) luminance for Laplacian edge detection. */
    private fun linearLuminance(pixel: Int): Double {
        return 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
    }

    /** sRGB gamma to linear light conversion (IEC 61966-2-1). */
    private fun srgbToLinear(c: Float): Float {
        return if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Variance over first [count] elements of a pre-allocated DoubleArray. No List boxing. */
    private fun varianceArray(values: DoubleArray, count: Int): Double {
        if (count == 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) sum += values[i]
        val mean = sum / count
        var sq = 0.0
        for (i in 0 until count) sq += (values[i] - mean) * (values[i] - mean)
        return sq / count
    }

    private fun scaleBitmap(bitmap: Bitmap, maxW: Int, maxH: Int): Bitmap {
        val scale = minOf(maxW.toFloat() / bitmap.width, maxH.toFloat() / bitmap.height, 1f)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, false)
    }
}
