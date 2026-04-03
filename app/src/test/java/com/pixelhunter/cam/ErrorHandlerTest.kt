package com.pixelhunter.cam

import androidx.test.core.app.ApplicationProvider
import com.pixelhunter.cam.util.ErrorHandler
import com.pixelhunter.cam.util.ErrorHandler.ErrorCategory
import com.pixelhunter.cam.util.onSuccess
import com.pixelhunter.cam.util.onFailure
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ErrorHandler utility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class ErrorHandlerTest {
    
    @Test
    fun `executeSafe returns success for valid operation`() = runBlocking {
        val result = ErrorHandler.executeSafe(
            operationName = "test_success",
            category = ErrorCategory.UNKNOWN
        ) {
            "success"
        }
        
        assertTrue(result.success)
        assertEquals("success", result.data)
        assertNull(result.error)
    }
    
    @Test
    fun `executeSafe handles IOException with retry`() = runBlocking {
        var attemptCount = 0
        
        val result = ErrorHandler.executeSafe(
            operationName = "test_io",
            category = ErrorCategory.STORAGE,
            maxAttempts = 3
        ) {
            attemptCount++
            if (attemptCount < 3) {
                throw IOException("Simulated IO error")
            }
            "recovered"
        }
        
        assertTrue(result.success)
        assertEquals("recovered", result.data)
        assertEquals(3, attemptCount)
    }
    
    @Test
    fun `executeSafe gives up after max retries`() = runBlocking {
        var attemptCount = 0
        
        val result = ErrorHandler.executeSafe(
            operationName = "test_fail",
            category = ErrorCategory.CAMERA,
            maxAttempts = 2
        ) {
            attemptCount++
            throw IOException("Persistent error")
        }
        
        assertFalse(result.success)
        assertNull(result.data)
        assertEquals(2, attemptCount)
        assertNotNull(result.errorMessage)
    }
    
    @Test
    fun `executeSafe handles OutOfMemory immediately`() = runBlocking {
        var attemptCount = 0
        
        val result = ErrorHandler.executeSafe(
            operationName = "test_oom",
            category = ErrorCategory.MEMORY
        ) {
            attemptCount++
            throw OutOfMemoryError("Simulated OOM")
        }
        
        assertFalse(result.success)
        assertEquals(1, attemptCount) // Should not retry OOM
        assertEquals(ErrorCategory.MEMORY, result.category)
    }
    
    @Test
    fun `executeSafe handles cancellation`() = runBlocking {
        val result = ErrorHandler.executeSafe(
            operationName = "test_cancel",
            category = ErrorCategory.UNKNOWN
        ) {
            throw kotlinx.coroutines.CancellationException("Cancelled")
        }
        
        assertFalse(result.success)
        assertEquals("Operation cancelled", result.errorMessage)
    }
    
    @Test
    fun `validateRange accepts valid value`() {
        val result = ErrorHandler.validateRange(50, "test", 0, 100)
        
        assertTrue(result.success)
        assertEquals(50, result.data)
    }
    
    @Test
    fun `validateRange rejects out of range`() {
        val result = ErrorHandler.validateRange(150, "test", 0, 100)
        
        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("test"))
        assertTrue(result.errorMessage.contains("0"))
        assertTrue(result.errorMessage.contains("100"))
    }
    
    @Test
    fun `validateNotEmpty accepts non-empty string`() {
        val result = ErrorHandler.validateNotEmpty("hello", "test")
        
        assertTrue(result.success)
        assertEquals("hello", result.data)
    }
    
    @Test
    fun `validateNotEmpty rejects empty string`() {
        val result = ErrorHandler.validateNotEmpty("", "test")
        
        assertFalse(result.success)
        assertTrue(result.errorMessage.contains("test"))
    }
    
    @Test
    fun `validateNotEmpty rejects null`() {
        val result = ErrorHandler.validateNotEmpty(null, "test")
        
        assertFalse(result.success)
    }
    
    @Test
    fun `executeSafeSync returns success`() {
        val result = ErrorHandler.executeSafeSync("test_sync") {
            42
        }
        
        assertTrue(result.success)
        assertEquals(42, result.data)
    }
    
    @Test
    fun `executeSafeSync handles exception`() {
        val result = ErrorHandler.executeSafeSync("test_sync_fail") {
            throw IllegalStateException("Test error")
        }
        
        assertFalse(result.success)
        assertNull(result.data)
        assertNotNull(result.error)
    }
    
    @Test
    fun `batchExecute aggregates results`() {
        val operations = listOf(
            "op1" to { "success1" },
            "op2" to { throw IOException("fail") },
            "op3" to { "success3" }
        )
        
        val result = ErrorHandler.batchExecute(operations)
        
        assertEquals(3, result.total)
        assertEquals(2, result.successful)
        assertEquals(1, result.failed)
        assertEquals(2f/3f, result.successRate, 0.01f)
    }
    
    @Test
    fun `error stats tracking`() {
        ErrorHandler.clearErrorStats()
        
        // First, generate some errors
        runBlocking {
            ErrorHandler.executeSafe("test_stat", ErrorCategory.CAMERA) {
                throw IOException("error1")
            }
            ErrorHandler.executeSafe("test_stat", ErrorCategory.CAMERA) {
                throw IOException("error2")
            }
        }
        
        val stats = ErrorHandler.getErrorStats()
        assertTrue(stats.containsKey("CAMERA:test_stat"))
        assertTrue(stats["CAMERA:test_stat"]!! >= 2)
        
        // Clear and verify
        ErrorHandler.clearErrorStats()
        assertTrue(ErrorHandler.getErrorStats().isEmpty())
    }
    
    @Test
    fun `onSuccess extension only runs on success`() {
        var called = false
        
        ErrorHandler.SafeResult(success = true, data = "value")
            .onSuccess { called = true }
        
        assertTrue(called)
    }
    
    @Test
    fun `onSuccess extension skips on failure`() {
        var called = false
        
        ErrorHandler.SafeResult<String>(success = false)
            .onSuccess { called = true }
        
        assertFalse(called)
    }
    
    @Test
    fun `onFailure extension only runs on failure`() {
        var called = false
        
        ErrorHandler.SafeResult<String>(success = false, errorMessage = "error")
            .onFailure { called = true }
        
        assertTrue(called)
    }
}
