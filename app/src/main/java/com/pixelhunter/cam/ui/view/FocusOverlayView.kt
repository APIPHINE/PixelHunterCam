package com.pixelhunter.cam.ui.view

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Visual feedback for tap-to-focus.
 * Shows an animated focus bracket that expands/contract on focus start,
 * then turns green on success or red on failure.
 */
class FocusOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 30
    }
    
    private var focusX = 0f
    private var focusY = 0f
    private var focusSize = 0f
    private var targetSize = 150f
    private var isFocusing = false
    private var focusResult: FocusResult = FocusResult.IDLE
    
    private var animator: ValueAnimator? = null
    
    enum class FocusResult {
        IDLE, FOCUSING, SUCCESS, FAILURE
    }
    
    private val colors = mapOf(
        FocusResult.IDLE to Color.WHITE,
        FocusResult.FOCUSING to Color.YELLOW,
        FocusResult.SUCCESS to Color.GREEN,
        FocusResult.FAILURE to Color.RED
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun showFocusPoint(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusResult = FocusResult.FOCUSING
        isFocusing = true
        focusSize = targetSize * 1.5f
        
        animator?.cancel()
        
        // Animate to contracted state
        animator = ValueAnimator.ofFloat(focusSize, targetSize).apply {
            duration = 300
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                focusSize = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (focusResult == FocusResult.FOCUSING) {
                        pulseAnimation()
                    }
                }
            })
            start()
        }
        
        invalidate()
    }
    
    fun setFocusResult(success: Boolean) {
        focusResult = if (success) FocusResult.SUCCESS else FocusResult.FAILURE
        isFocusing = false
        animator?.cancel()
        invalidate()
        
        // Auto-hide after delay
        postDelayed({ hide() }, 800)
    }
    
    private fun pulseAnimation() {
        if (!isFocusing) return
        
        animator = ValueAnimator.ofFloat(targetSize, targetSize * 0.9f, targetSize).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                focusSize = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
    
    fun hide() {
        animator?.cancel()
        focusResult = FocusResult.IDLE
        isFocusing = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (focusResult == FocusResult.IDLE) return
        
        val color = colors[focusResult] ?: Color.WHITE
        paint.color = color
        fillPaint.color = color
        
        val halfSize = focusSize / 2
        val cornerLength = focusSize * 0.3f
        
        // Draw focus brackets (corners only)
        val left = focusX - halfSize
        val top = focusY - halfSize
        val right = focusX + halfSize
        val bottom = focusY + halfSize
        
        // Top-left corner
        canvas.drawLine(left, top, left + cornerLength, top, paint)
        canvas.drawLine(left, top, left, top + cornerLength, paint)
        
        // Top-right corner
        canvas.drawLine(right - cornerLength, top, right, top, paint)
        canvas.drawLine(right, top, right, top + cornerLength, paint)
        
        // Bottom-left corner
        canvas.drawLine(left, bottom, left + cornerLength, bottom, paint)
        canvas.drawLine(left, bottom - cornerLength, left, bottom, paint)
        
        // Bottom-right corner
        canvas.drawLine(right - cornerLength, bottom, right, bottom, paint)
        canvas.drawLine(right, bottom - cornerLength, right, bottom, paint)
        
        // Semi-transparent fill
        if (focusResult == FocusResult.FOCUSING) {
            canvas.drawRect(left, top, right, bottom, fillPaint)
        }
        
        // Center dot when focused
        if (focusResult == FocusResult.SUCCESS) {
            canvas.drawCircle(focusX, focusY, 6f, paint.apply { style = Paint.Style.FILL })
            paint.style = Paint.Style.STROKE
        }
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
