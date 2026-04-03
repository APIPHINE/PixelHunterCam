package com.pixelhunter.cam.util

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Memory management utilities to prevent OOM crashes.
 * 
 * Features:
 * - Bitmap size estimation and tracking
 * - Automatic downscaling based on available memory
 * - Bitmap pool for reuse
 * - Memory pressure monitoring
 */
object MemoryManager {
    
    private const val TAG = "MemoryManager"
    
    // Maximum memory to use for bitmaps (25% of max heap)
    private const val MAX_BITMAP_MEMORY_RATIO = 0.25
    
    // Minimum free memory to maintain
    private const val MIN_FREE_MEMORY_MB = 50
    
    // Track allocated bitmap memory
    private val allocatedMemory = AtomicLong(0)
    private val bitmapRegistry = ConcurrentHashMap<Bitmap, Long>()
    
    data class BitmapSpec(
        val width: Int,
        val height: Int,
        val config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ) {
        fun calculateSize(): Long {
            val bytesPerPixel = when (config) {
                Bitmap.Config.ARGB_8888 -> 4L
                Bitmap.Config.RGB_565 -> 2L
                Bitmap.Config.ARGB_4444 -> 2L
                Bitmap.Config.ALPHA_8 -> 1L
                else -> 4L
            }
            return width.toLong() * height.toLong() * bytesPerPixel
        }
    }
    
    /**
     * Check if we can allocate a bitmap of the given size.
     */
    fun canAllocate(spec: BitmapSpec): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory
        
        val maxBitmapMemory = (maxMemory * MAX_BITMAP_MEMORY_RATIO).toLong()
        val requiredMemory = spec.calculateSize()
        
        val canAlloc = requiredMemory < availableMemory - (MIN_FREE_MEMORY_MB * 1024 * 1024) &&
                       allocatedMemory.get() + requiredMemory < maxBitmapMemory
        
        if (!canAlloc) {
            Log.w(TAG, "⚠️ Cannot allocate ${spec.width}x${spec.height} bitmap. " +
                       "Required: ${requiredMemory / 1024 / 1024}MB, " +
                       "Available: ${availableMemory / 1024 / 1024}MB")
        }
        
        return canAlloc
    }
    
    /**
     * Get recommended sample size for loading a bitmap to stay within memory limits.
     */
    fun getRecommendedSampleSize(originalWidth: Int, originalHeight: Int): Int {
        val runtime = Runtime.getRuntime()
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val maxBitmapMemory = (runtime.maxMemory() * MAX_BITMAP_MEMORY_RATIO).toLong()
        
        // Target: use no more than 1/8 of available bitmap memory for one image
        val targetMemory = maxBitmapMemory / 8
        val originalSize = originalWidth.toLong() * originalHeight * 4 // ARGB_8888
        
        if (originalSize <= targetMemory) return 1
        
        // Calculate sample size (power of 2)
        var sampleSize = 1
        while (originalSize / (sampleSize * sampleSize) > targetMemory && sampleSize < 16) {
            sampleSize *= 2
        }
        
        Log.d(TAG, "📐 Recommended sample size for ${originalWidth}x${originalHeight}: $sampleSize")
        return sampleSize
    }
    
    /**
     * Safely create a bitmap with memory checks.
     */
    fun createBitmap(
        width: Int,
        height: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
        name: String = "bitmap"
    ): Bitmap? {
        val spec = BitmapSpec(width, height, config)
        
        return try {
            if (!canAllocate(spec)) {
                Log.e(TAG, "💥 Cannot allocate $name: ${width}x${height}")
                return null
            }
            
            val bitmap = Bitmap.createBitmap(width, height, config)
            trackBitmap(bitmap, name)
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM creating $name: ${width}x${height}", e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error creating $name: ${e.message}")
            null
        }
    }
    
    /**
     * Safely load and scale a bitmap from file.
     */
    fun loadBitmapScaled(
        file: File,
        maxWidth: Int = 2048,
        maxHeight: Int = 2048,
        name: String = "loaded"
    ): Bitmap? {
        return try {
            // First decode bounds only
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(file.absolutePath, options)
            
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                Log.e(TAG, "❌ Invalid image dimensions in $file")
                return null
            }
            
            // Calculate sample size
            val sampleSize = calculateInSampleSize(options, maxWidth, maxHeight)
                .coerceAtLeast(getRecommendedSampleSize(options.outWidth, options.outHeight))
            
            // Decode with sample size
            val decodeOptions = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
            if (bitmap != null) {
                trackBitmap(bitmap, "$name(${file.name})")
            }
            bitmap
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM loading $file", e)
            System.gc()
            null
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error loading $file: ${e.message}")
            null
        }
    }
    
    /**
     * Safely recycle a bitmap and untrack it.
     */
    fun recycle(bitmap: Bitmap?, name: String = "bitmap") {
        bitmap?.let { bmp ->
            try {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                    untrackBitmap(bmp)
                    Log.v(TAG, "♻️ Recycled $name")
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error recycling $name: ${e.message}")
            }
        }
        Unit
    }
    
    /**
     * Track bitmap for memory accounting.
     */
    private fun trackBitmap(bitmap: Bitmap, name: String) {
        val size = bitmap.byteCount.toLong()
        bitmapRegistry[bitmap] = size
        allocatedMemory.addAndGet(size)
        Log.v(TAG, "📊 Tracked $name: ${size / 1024 / 1024}MB (total: ${allocatedMemory.get() / 1024 / 1024}MB)")
    }
    
    /**
     * Untrack bitmap when recycled.
     */
    private fun untrackBitmap(bitmap: Bitmap) {
        val size = bitmapRegistry.remove(bitmap) ?: 0L
        allocatedMemory.addAndGet(-size)
    }
    
    /**
     * Calculate optimal inSampleSize for BitmapFactory.
     */
    private fun calculateInSampleSize(
        options: android.graphics.BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            
            while (halfHeight / inSampleSize >= reqHeight &&
                   halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        
        return inSampleSize
    }
    
    /**
     * Get current memory status for debugging.
     */
    fun getMemoryStatus(): MemoryStatus {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory
        
        return MemoryStatus(
            maxMemoryMb = maxMemory / 1024 / 1024,
            usedMemoryMb = usedMemory / 1024 / 1024,
            freeMemoryMb = (maxMemory - usedMemory) / 1024 / 1024,
            allocatedBitmapsMb = allocatedMemory.get() / 1024 / 1024,
            trackedBitmapCount = bitmapRegistry.size
        )
    }
    
    data class MemoryStatus(
        val maxMemoryMb: Long,
        val usedMemoryMb: Long,
        val freeMemoryMb: Long,
        val allocatedBitmapsMb: Long,
        val trackedBitmapCount: Int
    ) {
        fun isLowMemory(): Boolean = freeMemoryMb < MIN_FREE_MEMORY_MB
        fun getUsagePercent(): Int = ((usedMemoryMb.toFloat() / maxMemoryMb) * 100).toInt()
    }
    
    /**
     * Log current memory status.
     */
    fun logMemoryStatus() {
        val status = getMemoryStatus()
        val emoji = when {
            status.isLowMemory() -> "🔴"
            status.getUsagePercent() > 80 -> "🟡"
            else -> "🟢"
        }
        Log.d(TAG, "$emoji Memory: ${status.usedMemoryMb}MB/${status.maxMemoryMb}MB used, " +
                   "${status.freeMemoryMb}MB free, ${status.allocatedBitmapsMb}MB bitmaps, " +
                   "${status.trackedBitmapCount} tracked")
    }
    
    /**
     * Clear all tracked bitmaps (use with caution).
     */
    fun clearAll() {
        bitmapRegistry.keys.forEach { bitmap ->
            try {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
            } catch (e: Exception) {
                Log.w(TAG, "⚠️ Error clearing bitmap: ${e.message}")
            }
        }
        bitmapRegistry.clear()
        allocatedMemory.set(0)
        System.gc()
        Log.i(TAG, "🧹 Cleared all tracked bitmaps")
    }
    
    /**
     * Execute a memory-intensive operation with automatic recovery.
     */
    fun <T> withMemoryGuard(
        operationName: String,
        onLowMemory: () -> T? = { null },
        operation: () -> T
    ): T? {
        return try {
            if (getMemoryStatus().isLowMemory()) {
                Log.w(TAG, "⚠️ Low memory before $operationName, requesting GC")
                System.gc()
                Thread.sleep(100)
                
                if (getMemoryStatus().isLowMemory()) {
                    Log.e(TAG, "🔴 Still low memory after GC, aborting $operationName")
                    return onLowMemory()
                }
            }
            operation()
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 OOM during $operationName", e)
            System.gc()
            onLowMemory()
        }
    }
}

/**
 * Extension to safely use a bitmap and auto-recycle.
 */
inline fun <T> Bitmap.useAndRecycle(block: (Bitmap) -> T): T {
    return try {
        block(this)
    } finally {
        if (!isRecycled) {
            recycle()
        }
    }
}
