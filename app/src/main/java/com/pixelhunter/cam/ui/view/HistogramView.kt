package com.pixelhunter.cam.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Real-time histogram display for exposure analysis.
 * Shows RGB channels and luminance distribution.
 */
class HistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    
    // Histogram data (256 bins)
    private var redHistogram = IntArray(256)
    private var greenHistogram = IntArray(256)
    private var blueHistogram = IntArray(256)
    private var luminanceHistogram = IntArray(256)
    
    var showRgb = true
        set(value) {
            field = value
            invalidate()
        }
    
    var isVisible = true
        set(value) {
            field = value
            invalidate()
        }

    private val redPaint = Paint().apply {
        color = Color.argb(120, 255, 50, 50)
        style = Paint.Style.FILL
    }
    
    private val greenPaint = Paint().apply {
        color = Color.argb(120, 50, 255, 50)
        style = Paint.Style.FILL
    }
    
    private val bluePaint = Paint().apply {
        color = Color.argb(120, 50, 50, 255)
        style = Paint.Style.FILL
    }
    
    private val luminancePaint = Paint().apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.FILL
    }
    
    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
    }
    
    private val backgroundPaint = Paint().apply {
        color = Color.argb(120, 0, 0, 0)
        style = Paint.Style.FILL
    }

    /**
     * Update histogram from bitmap pixels.
     * Call this from a background thread after processing a frame.
     */
    fun updateFromPixels(pixels: IntArray) {
        // Reset histograms
        redHistogram.fill(0)
        greenHistogram.fill(0)
        blueHistogram.fill(0)
        luminanceHistogram.fill(0)
        
        for (pixel in pixels) {
            val r = Color.red(pixel)
            val g = Color.green(pixel)
            val b = Color.blue(pixel)
            
            redHistogram[r]++
            greenHistogram[g]++
            blueHistogram[b]++
            
            // Luminance (Rec. 709)
            val y = (0.2126 * r + 0.7152 * g + 0.0722 * b).toInt()
            luminanceHistogram[y.coerceIn(0, 255)]++
        }
        
        postInvalidate()
    }
    
    /**
     * Update histogram from analysis result.
     * Simplified version that just shows exposure zones.
     */
    fun updateFromAnalysis(luminance: Float, shadows: Float, highlights: Float) {
        // Simplified histogram visualization based on analysis
        luminanceHistogram.fill(0)
        
        val peak = (luminance * 255).toInt().coerceIn(0, 255)
        
        // Create gaussian-like distribution around the average
        for (i in 0..255) {
            val distance = abs(i - peak)
            val value = (1000 * kotlin.math.exp(-distance * distance / 2000.0)).toInt()
            luminanceHistogram[i] = value
        }
        
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Background
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        
        // Grid lines
        for (i in 1..3) {
            val x = width * i / 4
            canvas.drawLine(x, 0f, x, height, gridPaint)
        }
        
        // Find max for normalization
        val maxValue = maxOf(
            redHistogram.maxOrNull() ?: 1,
            greenHistogram.maxOrNull() ?: 1,
            blueHistogram.maxOrNull() ?: 1,
            luminanceHistogram.maxOrNull() ?: 1,
            1
        )
        
        // Draw histogram
        if (showRgb) {
            drawChannel(canvas, redHistogram, maxValue, width, height, redPaint)
            drawChannel(canvas, greenHistogram, maxValue, width, height, greenPaint)
            drawChannel(canvas, blueHistogram, maxValue, width, height, bluePaint)
        } else {
            drawChannel(canvas, luminanceHistogram, maxValue, width, height, luminancePaint)
        }
    }
    
    private fun drawChannel(
        canvas: Canvas,
        histogram: IntArray,
        maxValue: Int,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        if (histogram.isEmpty()) return
        
        path.reset()
        path.moveTo(0f, height)
        
        val binWidth = width / histogram.size
        
        for (i in histogram.indices) {
            val normalizedHeight = (histogram[i].toFloat() / maxValue) * height * 0.9f
            val x = i * binWidth
            val y = height - normalizedHeight
            path.lineTo(x, y)
        }
        
        path.lineTo(width, height)
        path.close()
        
        canvas.drawPath(path, paint)
    }
    
    /**
     * Get exposure zone summary.
     * Returns a map of shadow/mid/highlight percentages.
     */
    fun getExposureZones(): Map<String, Float> {
        val total = luminanceHistogram.sum().toFloat()
        if (total == 0f) return mapOf("shadows" to 0f, "mids" to 1f, "highlights" to 0f)
        
        val shadows = luminanceHistogram.slice(0..63).sum() / total
        val mids = luminanceHistogram.slice(64..191).sum() / total
        val highlights = luminanceHistogram.slice(192..255).sum() / total
        
        return mapOf(
            "shadows" to shadows,
            "mids" to mids,
            "highlights" to highlights
        )
    }
}

private fun abs(x: Int): Int = if (x < 0) -x else x
