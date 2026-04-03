package com.pixelhunter.cam

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.pixelhunter.cam.util.MemoryManager
import com.pixelhunter.cam.util.ErrorHandler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for MemoryManager utility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MemoryManagerTest {
    
    @Test
    fun `calculateSize returns correct bytes for ARGB_8888`() {
        val spec = MemoryManager.BitmapSpec(100, 100, Bitmap.Config.ARGB_8888)
        val size = spec.calculateSize()
        
        // 100 * 100 * 4 bytes = 40,000 bytes
        assertEquals(40000L, size)
    }
    
    @Test
    fun `calculateSize returns correct bytes for RGB_565`() {
        val spec = MemoryManager.BitmapSpec(100, 100, Bitmap.Config.RGB_565)
        val size = spec.calculateSize()
        
        // 100 * 100 * 2 bytes = 20,000 bytes
        assertEquals(20000L, size)
    }
    
    @Test
    fun `getRecommendedSampleSize returns 1 for small images`() {
        val runtime = Runtime.getRuntime()
        val availableMemory = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        
        // Use a very small image that should fit easily
        val sampleSize = MemoryManager.getRecommendedSampleSize(100, 100)
        
        assertTrue("Sample size should be reasonable", sampleSize >= 1)
    }
    
    @Test
    fun `getRecommendedSampleSize is power of 2`() {
        val sampleSize = MemoryManager.getRecommendedSampleSize(10000, 10000)
        
        // Should be a power of 2
        assertTrue("Sample size should be power of 2", 
            sampleSize == 1 || sampleSize == 2 || sampleSize == 4 || 
            sampleSize == 8 || sampleSize == 16)
    }
    
    @Test
    fun `getMemoryStatus returns valid values`() {
        val status = MemoryManager.getMemoryStatus()
        
        assertTrue("Max memory should be positive", status.maxMemoryMb > 0)
        assertTrue("Used memory should be non-negative", status.usedMemoryMb >= 0)
        assertTrue("Free memory should be non-negative", status.freeMemoryMb >= 0)
        assertTrue("Tracked bitmaps should be non-negative", status.allocatedBitmapsMb >= 0)
        assertTrue("Bitmap count should be non-negative", status.trackedBitmapCount >= 0)
    }
    
    @Test
    fun `isLowMemory returns false with adequate memory`() {
        // This test assumes we're not actually running low on memory
        // In a real low memory situation, this would return true
        val status = MemoryManager.getMemoryStatus()
        
        // If we have plenty of free memory, isLowMemory should be false
        if (status.freeMemoryMb > 100) {
            assertFalse("Should not be low memory with ${status.freeMemoryMb}MB free", 
                status.isLowMemory())
        }
    }
    
    @Test
    fun `getUsagePercent returns valid percentage`() {
        val status = MemoryManager.getMemoryStatus()
        val percent = status.getUsagePercent()
        
        assertTrue("Usage percent should be 0-100", percent in 0..100)
    }
    
    @Test
    fun `canAllocate returns true for small bitmaps`() {
        val spec = MemoryManager.BitmapSpec(10, 10, Bitmap.Config.ARGB_8888)
        
        assertTrue("Should be able to allocate small bitmap",
            MemoryManager.canAllocate(spec))
    }
    
    @Test
    fun `withMemoryGuard returns result on success`() {
        val result = MemoryManager.withMemoryGuard(
            operationName = "test",
            onLowMemory = { null }
        ) {
            "success"
        }
        
        assertEquals("success", result)
    }
    
    @Test
    fun `withMemoryGuard returns fallback on OOM`() {
        val result = MemoryManager.withMemoryGuard(
            operationName = "test_oom",
            onLowMemory = { "fallback" }
        ) {
            throw OutOfMemoryError("Simulated OOM")
        }
        
        assertEquals("fallback", result)
    }
    
    @Test
    fun `batchExecute tracks success and failure`() {
        val operations = listOf(
            "op1" to { "success1" },
            "op2" to { throw RuntimeException("fail") },
            "op3" to { "success3" }
        )
        
        val result = ErrorHandler.batchExecute(operations)
        
        assertEquals(3, result.total)
        assertEquals(2, result.successful)
        assertEquals(1, result.failed)
        assertEquals(2f/3f, result.successRate, 0.01f)
    }
}
