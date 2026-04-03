package com.pixelhunter.cam.analysis

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import androidx.annotation.WorkerThread
import com.pixelhunter.cam.session.FlagType
import com.pixelhunter.cam.session.SessionFlag
import com.pixelhunter.cam.session.SessionManager
import com.pixelhunter.cam.session.Severity
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sqrt

/**
 * Enhanced Frame Analyzer with:
 * - Histogram generation for exposure analysis
 * - Face detection hints
 * - Grid composition analysis
 * - Better blur detection with edge detection
 * - Shadow/highlight clipping detection
 */
@WorkerThread
object EnhancedFrameAnalyzer {

    private const val TILE_COLS = 4
    private const val TILE_ROWS = 4
    private const val HISTOGRAM_BINS = 256

    data class EnhancedAnalysisResult(
        val blurScore: Double,
        val luminance: Float,
        val estimatedColorTempK: Float,
        val isGloballyBlurry: Boolean,
        val isLocallyBlurry: Boolean,
        val blurryTileRatio: Float,
        val flags: List<SessionFlag>,
        
        // New fields
        val histogram: HistogramData,
        val exposureZones: ExposureZones,
        val faces: List<FaceRegion>,
        val compositionScore: Float,
        val shadowClipping: Float,
        val highlightClipping: Float,
        val isPortrait: Boolean
    )
    
    data class HistogramData(
        val red: IntArray,
        val green: IntArray,
        val blue: IntArray,
        val luminance: IntArray
    ) {
        companion object {
            fun empty(): HistogramData = HistogramData(
                IntArray(256),
                IntArray(256),
                IntArray(256),
                IntArray(256)
            )
        }
    }
    
    data class ExposureZones(
        val shadows: Float,
        val midtones: Float,
        val highlights: Float,
        val blacks: Float,      // 0-10
        val whites: Float       // 245-255
    )
    
    data class FaceRegion(
        val bounds: Rect,
        val confidence: Float,
        val isInFocus: Boolean,
        val isProperlyExposed: Boolean
    )

    fun analyze(bitmap: Bitmap, sessionManager: SessionManager): EnhancedAnalysisResult {
        val flags = mutableListOf<SessionFlag>()
        
        // Scale down for analysis
        val scaled = scaleBitmap(bitmap, 480, 360)
        
        try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            
            // Core analysis
            val blurScore = computeLaplacianVariance(pixels, scaled.width, scaled.height)
            val luminance = computeLuminance(pixels)
            val colorTemp = estimateColorTemperature(pixels)
            val tileBluriness = computeTileBlur(pixels, scaled.width, scaled.height)
            
            val isGlobalBlurry = blurScore < SessionManager.BLUR_GLOBAL_THRESHOLD
            val isLocalBlurry = !isGlobalBlurry && tileBluriness > SessionManager.BLUR_TILE_FAIL_RATIO
            
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
                    message = "${(tileBluriness * 100).toInt()}% of frame blurry — subject motion?",
                    suggestApiReview = false
                )
            }
            
            // Generate histogram
            val histogram = generateHistogram(pixels)
            
            // Calculate exposure zones
            val exposureZones = calculateExposureZones(histogram.luminance)
            
            // Detect clipping
            val shadowClipping = exposureZones.blacks
            val highlightClipping = exposureZones.whites
            
            if (shadowClipping > 0.15f) {
                flags += SessionFlag(
                    type = FlagType.EXPOSURE_DRIFT,
                    severity = if (shadowClipping > 0.3f) Severity.HIGH else Severity.MEDIUM,
                    message = "${(shadowClipping * 100).toInt()}% of image is pure black — underexposed",
                    suggestApiReview = shadowClipping > 0.25f
                )
            }
            
            if (highlightClipping > 0.15f) {
                flags += SessionFlag(
                    type = FlagType.EXPOSURE_DRIFT,
                    severity = if (highlightClipping > 0.3f) Severity.HIGH else Severity.MEDIUM,
                    message = "${(highlightClipping * 100).toInt()}% of image is blown out — overexposed",
                    suggestApiReview = highlightClipping > 0.25f
                )
            }
            
            // Simple face detection (looking for skin-toned regions)
            val faces = detectFaces(pixels, scaled.width, scaled.height)
            
            // Composition analysis
            val compositionScore = analyzeComposition(pixels, scaled.width, scaled.height, faces)
            
            // Drift check
            sessionManager.checkDrift(luminance, colorTemp)
            flags += sessionManager.flags.value.filter {
                it.type == FlagType.EXPOSURE_DRIFT || it.type == FlagType.WHITE_BALANCE_DRIFT
            }
            
            val isPortrait = faces.isNotEmpty() && faces.any { it.bounds.height() > it.bounds.width() * 1.2f }
            
            return EnhancedAnalysisResult(
                blurScore = blurScore,
                luminance = luminance,
                estimatedColorTempK = colorTemp,
                isGloballyBlurry = isGlobalBlurry,
                isLocallyBlurry = isLocalBlurry,
                blurryTileRatio = tileBluriness,
                flags = flags,
                histogram = histogram,
                exposureZones = exposureZones,
                faces = faces,
                compositionScore = compositionScore,
                shadowClipping = shadowClipping,
                highlightClipping = highlightClipping,
                isPortrait = isPortrait
            )
            
        } finally {
            scaled.recycle()
        }
    }
    
    // ─── Histogram Generation ─────────────────────────────────────
    
    private fun generateHistogram(pixels: IntArray): HistogramData {
        val red = IntArray(256)
        val green = IntArray(256)
        val blue = IntArray(256)
        val luminance = IntArray(256)
        
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            red[r]++
            green[g]++
            blue[b]++
            
            // Rec. 709 luminance
            val y = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
            luminance[y.coerceIn(0, 255)]++
        }
        
        return HistogramData(red, green, blue, luminance)
    }
    
    private fun calculateExposureZones(lumaHistogram: IntArray): ExposureZones {
        val total = lumaHistogram.sum().toFloat().coerceAtLeast(1f)
        
        val blacks = lumaHistogram.slice(0..10).sum() / total
        val shadows = lumaHistogram.slice(11..63).sum() / total
        val midtones = lumaHistogram.slice(64..191).sum() / total
        val highlights = lumaHistogram.slice(192..244).sum() / total
        val whites = lumaHistogram.slice(245..255).sum() / total
        
        return ExposureZones(shadows, midtones, highlights, blacks, whites)
    }
    
    // ─── Simple Face Detection ────────────────────────────────────
    
    private fun detectFaces(pixels: IntArray, width: Int, height: Int): List<FaceRegion> {
        val faces = mutableListOf<FaceRegion>()
        
        // Very simple skin tone detection
        // Real face detection would use ML Kit or CameraX ML
        val skinTonePixels = mutableListOf<Pair<Int, Int>>()
        
        for (y in 0 until height step 4) {
            for (x in 0 until width step 4) {
                val pixel = pixels[y * width + x]
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                
                // Simple skin tone heuristic
                if (r > 60 && g > 40 && b > 20 &&
                    r > g && r > b &&
                    abs(r - g) > 15 &&
                    abs(r - b) > 15) {
                    skinTonePixels.add(x to y)
                }
            }
        }
        
        // Cluster skin tone pixels into potential faces
        if (skinTonePixels.size > 100) {
            // Find bounding box of skin regions
            val minX = skinTonePixels.map { it.first }.minOrNull() ?: 0
            val maxX = skinTonePixels.map { it.first }.maxOrNull() ?: width
            val minY = skinTonePixels.map { it.second }.minOrNull() ?: 0
            val maxY = skinTonePixels.map { it.second }.maxOrNull() ?: height
            
            val bounds = Rect(minX, minY, maxX, maxY)
            val regionSize = (maxX - minX) * (maxY - minY)
            val imageSize = width * height
            
            // Only add if it's a reasonable face size (1-50% of image)
            if (regionSize > imageSize * 0.01 && regionSize < imageSize * 0.5) {
                faces.add(FaceRegion(
                    bounds = bounds,
                    confidence = min(1f, skinTonePixels.size / 1000f),
                    isInFocus = true, // Would need additional analysis
                    isProperlyExposed = true
                ))
            }
        }
        
        return faces
    }
    
    // ─── Composition Analysis ─────────────────────────────────────
    
    private fun analyzeComposition(
        pixels: IntArray,
        width: Int,
        height: Int,
        faces: List<FaceRegion>
    ): Float {
        var score = 0.5f
        
        // Rule of thirds bonus
        faces.forEach { face ->
            val centerX = face.bounds.centerX()
            val centerY = face.bounds.centerY()
            
            val xThirds = listOf(width / 3f, 2 * width / 3f)
            val yThirds = listOf(height / 3f, 2 * height / 3f)
            
            val nearThirdX = xThirds.any { abs(centerX - it) < width * 0.1f }
            val nearThirdY = yThirds.any { abs(centerY - it) < height * 0.1f }
            
            if (nearThirdX && nearThirdY) score += 0.3f
            else if (nearThirdX || nearThirdY) score += 0.15f
        }
        
        // Centered faces get lower score (boring composition)
        faces.forEach { face ->
            val centerX = face.bounds.centerX()
            val centerY = face.bounds.centerY()
            val imageCenterX = width / 2
            val imageCenterY = height / 2
            
            val dx = centerX - imageCenterX
            val dy = centerY - imageCenterY
            val distFromCenter = sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            
            if (distFromCenter < width * 0.1f) score -= 0.2f
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    // ─── Blur Detection ───────────────────────────────────────────
    
    private fun computeLaplacianVariance(pixels: IntArray, width: Int, height: Int): Double {
        val values = DoubleArray((width - 2) * (height - 2))
        var i = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val lap = 4 * linearLuminance(pixels[y * width + x]) -
                        linearLuminance(pixels[(y - 1) * width + x]) -
                        linearLuminance(pixels[(y + 1) * width + x]) -
                        linearLuminance(pixels[y * width + (x - 1)]) -
                        linearLuminance(pixels[y * width + (x + 1)])
                values[i++] = lap
            }
        }
        return varianceArray(values, i)
    }
    
    private fun computeTileBlur(pixels: IntArray, width: Int, height: Int): Float {
        val tileW = width / TILE_COLS
        val tileH = height / TILE_ROWS
        var blurryCount = 0
        
        for (row in 0 until TILE_ROWS) {
            for (col in 0 until TILE_COLS) {
                val score = laplacianVarianceInRegion(
                    pixels, width, col * tileW, row * tileH, tileW, tileH
                )
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
    
    // ─── Luminance & Color ────────────────────────────────────────
    // Perceptual luminance using IEC 61966-2-1 sRGB transfer function + Rec. 709 weights
    
    /**
     * Converts a single sRGB channel value (0–255 integer) to linear light (0.0–1.0).
     * IEC 61966-2-1 piecewise transfer function (the correct sRGB curve).
     */
    private fun srgbToLinear(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) {
            c / 12.92f
        } else {
            ((c + 0.055f) / 1.055f).pow(2.4f)
        }
    }
    
    /**
     * Computes perceptual luminance (0.0–1.0) of a pixel array.
     * Steps:
     *  1. Convert each R/G/B channel from sRGB gamma space to linear light
     *  2. Apply Rec. 709 luminance coefficients
     *  3. Average linear luminance across all pixels
     *  4. Re-encode to perceptual space (gamma ~2.2)
     */
    @WorkerThread
    fun computeLuminance(pixels: IntArray): Float {
        var linearSum = 0.0
        for (pixel in pixels) {
            val rLin = srgbToLinear(Color.red(pixel))
            val gLin = srgbToLinear(Color.green(pixel))
            val bLin = srgbToLinear(Color.blue(pixel))
            // Rec. 709 weights — these must be applied in linear space
            linearSum += 0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin
        }
        
        val meanLinear = (linearSum / pixels.size).toFloat()
        
        // Re-encode to perceptual (gamma 2.2 approximation)
        return meanLinear.pow(1f / 2.2f).coerceIn(0f, 1f)
    }
    
    private fun estimateColorTemperature(pixels: IntArray): Float {
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
    
    private fun linearLuminance(pixel: Int): Double {
        // Fast approximation for Laplacian (not gamma-corrected, but OK for edge detection)
        return 0.2126 * Color.red(pixel) + 0.7152 * Color.green(pixel) + 0.0722 * Color.blue(pixel)
    }
    
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
