package com.pixelhunter.cam.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Composition grid overlay for the camera preview.
 * Supports: Rule of Thirds, Golden Ratio, Square, and Center crosshair.
 */
class GridOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class GridMode {
        NONE,
        RULE_OF_THIRDS,
        GOLDEN_RATIO,
        SQUARE,
        CROSSHAIR
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        alpha = 120
    }
    
    private val strongPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        alpha = 180
    }

    var gridMode: GridMode = GridMode.RULE_OF_THIRDS
        set(value) {
            field = value
            invalidate()
        }
    
    var isVisible: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    fun cycleMode(): GridMode {
        val values = GridMode.values()
        val nextIndex = (gridMode.ordinal + 1) % values.size
        gridMode = values[nextIndex]
        return gridMode
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible || gridMode == GridMode.NONE) return
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        when (gridMode) {
            GridMode.RULE_OF_THIRDS -> drawRuleOfThirds(canvas, width, height)
            GridMode.GOLDEN_RATIO -> drawGoldenRatio(canvas, width, height)
            GridMode.SQUARE -> drawSquare(canvas, width, height)
            GridMode.CROSSHAIR -> drawCrosshair(canvas, width, height)
            GridMode.NONE -> {}
        }
    }
    
    private fun drawRuleOfThirds(canvas: Canvas, width: Float, height: Float) {
        val x1 = width / 3
        val x2 = x1 * 2
        val y1 = height / 3
        val y2 = y1 * 2
        
        // Vertical lines
        canvas.drawLine(x1, 0f, x1, height, paint)
        canvas.drawLine(x2, 0f, x2, height, paint)
        
        // Horizontal lines
        canvas.drawLine(0f, y1, width, y1, paint)
        canvas.drawLine(0f, y2, width, y2, paint)
    }
    
    private fun drawGoldenRatio(canvas: Canvas, width: Float, height: Float) {
        val phi = 1.618f
        
        val x1 = width / phi
        val x2 = width - x1
        val y1 = height / phi
        val y2 = height - y1
        
        // Vertical lines
        canvas.drawLine(x1, 0f, x1, height, paint)
        canvas.drawLine(x2, 0f, x2, height, paint)
        
        // Horizontal lines
        canvas.drawLine(0f, y1, width, y1, paint)
        canvas.drawLine(0f, y2, width, y2, paint)
        
        // Diagonal guides
        canvas.drawLine(0f, 0f, width, height, paint.apply { alpha = 60 })
        canvas.drawLine(width, 0f, 0f, height, paint)
        paint.alpha = 120
    }
    
    private fun drawSquare(canvas: Canvas, width: Float, height: Float) {
        val size = minOf(width, height)
        val left = (width - size) / 2
        val top = (height - size) / 2
        val right = left + size
        val bottom = top + size
        
        // Square outline
        canvas.drawLine(left, top, right, top, strongPaint)
        canvas.drawLine(right, top, right, bottom, strongPaint)
        canvas.drawLine(right, bottom, left, bottom, strongPaint)
        canvas.drawLine(left, bottom, left, top, strongPaint)
        
        // Center cross
        val centerX = width / 2
        val centerY = height / 2
        val crossSize = size / 6
        
        canvas.drawLine(centerX - crossSize, centerY, centerX + crossSize, centerY, strongPaint)
        canvas.drawLine(centerX, centerY - crossSize, centerX, centerY + crossSize, strongPaint)
    }
    
    private fun drawCrosshair(canvas: Canvas, width: Float, height: Float) {
        val centerX = width / 2
        val centerY = height / 2
        val armLength = minOf(width, height) / 8
        
        // Horizontal
        canvas.drawLine(centerX - armLength, centerY, centerX - 20, centerY, strongPaint)
        canvas.drawLine(centerX + 20, centerY, centerX + armLength, centerY, strongPaint)
        
        // Vertical
        canvas.drawLine(centerX, centerY - armLength, centerX, centerY - 20, strongPaint)
        canvas.drawLine(centerX, centerY + 20, centerX, centerY + armLength, strongPaint)
        
        // Center circle
        canvas.drawCircle(centerX, centerY, 8f, paint.apply { style = Paint.Style.STROKE })
    }
}
