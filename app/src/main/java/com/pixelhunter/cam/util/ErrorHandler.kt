package com.pixelhunter.cam.util

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.lang.OutOfMemoryError
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized error handling and crash prevention utility.
 * 
 * Features:
 * - Safe wrapper for crash-prone operations
 * - Automatic retry with exponential backoff
 * - Error categorization and logging
 * - Graceful degradation
 */
object ErrorHandler {
    
    private const val TAG = "ErrorHandler"
    
    // Error tracking for analytics/debugging
    private val errorCounts = ConcurrentHashMap<String, Int>()
    private const val maxRetries = 3
    
    enum class ErrorSeverity {
        INFO,       // Log only
        WARNING,    // Log + notify user
        ERROR,      // Log + notify + degrade gracefully
        CRITICAL    // Log + crash app (should never happen)
    }
    
    enum class ErrorCategory {
        CAMERA,     // Camera operations
        STORAGE,    // File/MediaStore operations
        GPS,        // Location services
        MEMORY,     // Out of memory
        NETWORK,    // API calls (future)
        JSON,       // Serialization
        EXIF,       // EXIF operations
        UNKNOWN
    }
    
    data class SafeResult<T>(
        val success: Boolean,
        val data: T? = null,
        val error: Throwable? = null,
        val errorMessage: String = "",
        val category: ErrorCategory = ErrorCategory.UNKNOWN
    )
    
    /**
     * Execute an operation safely with automatic retry and error handling.
     * 
     * @param operationName Name for logging
     * @param category Error category for classification
     * @param maxAttempts Number of retry attempts
     * @param operation The suspend operation to execute
     * @return SafeResult containing either data or error info
     */
    suspend fun <T> executeSafe(
        operationName: String,
        category: ErrorCategory = ErrorCategory.UNKNOWN,
        maxAttempts: Int = maxRetries,
        operation: suspend () -> T
    ): SafeResult<T> {
        var lastException: Throwable? = null
        
        repeat(maxAttempts) { attempt ->
            try {
                val result = operation()
                if (attempt > 0) {
                    Log.i(TAG, "✅ $operationName succeeded on attempt ${attempt + 1}")
                }
                return SafeResult(success = true, data = result, category = category)
            } catch (e: CancellationException) {
                // Don't retry on coroutine cancellation
                return SafeResult(
                    success = false,
                    error = e,
                    errorMessage = "Operation cancelled",
                    category = category
                )
            } catch (e: TimeoutCancellationException) {
                lastException = e
                Log.w(TAG, "⏱️ $operationName timed out (attempt ${attempt + 1}/$maxAttempts)")
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(100L * (attempt + 1)) // Exponential backoff
                }
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "💥 $operationName: Out of memory!", e)
                System.gc() // Request garbage collection
                lastException = e
                return SafeResult(
                    success = false,
                    error = e,
                    errorMessage = "Not enough memory. Try closing other apps.",
                    category = ErrorCategory.MEMORY
                )
            } catch (e: IOException) {
                lastException = e
                logError(operationName, e, ErrorSeverity.WARNING, category, attempt + 1, maxAttempts)
                Log.w(TAG, "📁 $operationName IO error (attempt ${attempt + 1}/$maxAttempts): ${e.message}")
                if (attempt < maxAttempts - 1) {
                    kotlinx.coroutines.delay(200L * (attempt + 1))
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "🔒 $operationName permission denied", e)
                return SafeResult(
                    success = false,
                    error = e,
                    errorMessage = "Permission denied. Check app permissions.",
                    category = category
                )
            } catch (e: Exception) {
                lastException = e
                val severity = classifySeverity(e, category)
                logError(operationName, e, severity, category, attempt + 1, maxAttempts)
                
                if (attempt < maxAttempts - 1 && shouldRetry(e)) {
                    kotlinx.coroutines.delay(100L * (attempt + 1))
                }
            }
        }
        
        // All retries exhausted
        val errorMsg = when (category) {
            ErrorCategory.CAMERA -> "Camera operation failed. Try restarting the app."
            ErrorCategory.STORAGE -> "Could not save file. Check storage space."
            ErrorCategory.GPS -> "Location service unavailable. Check GPS settings."
            ErrorCategory.MEMORY -> "Out of memory. Close other apps and retry."
            ErrorCategory.JSON -> "Data processing error. Please try again."
            ErrorCategory.EXIF -> "Metadata error. Image saved without EXIF."
            else -> "Operation failed after $maxAttempts attempts."
        }
        
        return SafeResult(
            success = false,
            error = lastException,
            errorMessage = errorMsg,
            category = category
        )
    }
    
    /**
     * Execute operation synchronously without retry (for non-suspend functions).
     */
    fun <T> executeSafeSync(
        operationName: String,
        category: ErrorCategory = ErrorCategory.UNKNOWN,
        operation: () -> T
    ): SafeResult<T> {
        return try {
            SafeResult(success = true, data = operation(), category = category)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "💥 $operationName: Out of memory!", e)
            System.gc()
            SafeResult(
                success = false,
                error = e,
                errorMessage = "Not enough memory",
                category = ErrorCategory.MEMORY
            )
        } catch (e: Exception) {
            val severity = classifySeverity(e, category)
            logError(operationName, e, severity, category, 1, 1)
            SafeResult(
                success = false,
                error = e,
                errorMessage = e.message ?: "Unknown error",
                category = category
            )
        }
    }
    
    /**
     * Validate that a value is within acceptable range.
     */
    fun <T : Comparable<T>> validateRange(
        value: T,
        name: String,
        min: T,
        max: T
    ): SafeResult<T> {
        return if (value in min..max) {
            SafeResult(success = true, data = value)
        } else {
            SafeResult(
                success = false,
                errorMessage = "$name must be between $min and $max, got $value",
                category = ErrorCategory.UNKNOWN
            )
        }
    }
    
    /**
     * Validate string is not empty/null.
     */
    fun validateNotEmpty(value: String?, name: String): SafeResult<String> {
        return if (!value.isNullOrBlank()) {
            SafeResult(success = true, data = value)
        } else {
            SafeResult(
                success = false,
                errorMessage = "$name cannot be empty",
                category = ErrorCategory.UNKNOWN
            )
        }
    }
    
    /**
     * Safely close resources without throwing.
     */
    fun closeQuietly(closeable: AutoCloseable?, name: String = "resource") {
        try {
            closeable?.close()
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Error closing $name: ${e.message}")
        }
    }
    
    /**
     * Check if we should retry based on exception type.
     */
    private fun shouldRetry(e: Exception): Boolean {
        return when (e) {
            is IOException -> true
            is java.util.concurrent.TimeoutException -> true
            else -> false
        }
    }
    
    /**
     * Classify error severity based on type and context.
     */
    private fun classifySeverity(e: Exception, category: ErrorCategory): ErrorSeverity {
        return when {
            e is OutOfMemoryError -> ErrorSeverity.CRITICAL
            e is SecurityException -> ErrorSeverity.ERROR
            category == ErrorCategory.CAMERA && e is IllegalStateException -> ErrorSeverity.ERROR
            category == ErrorCategory.STORAGE && e is IOException -> ErrorSeverity.WARNING
            else -> ErrorSeverity.WARNING
        }
    }
    
    /**
     * Log error with appropriate level.
     */
    private fun logError(
        operation: String,
        error: Throwable,
        severity: ErrorSeverity,
        category: ErrorCategory,
        attempt: Int,
        maxAttempts: Int
    ) {
        val key = "$category:$operation"
        errorCounts[key] = (errorCounts[key] ?: 0) + 1
        
        val message = "❌ $operation failed ($attempt/$maxAttempts) [${category.name}]: ${error.message}"
        
        when (severity) {
            ErrorSeverity.INFO -> Log.i(TAG, message, error)
            ErrorSeverity.WARNING -> Log.w(TAG, message, error)
            ErrorSeverity.ERROR -> Log.e(TAG, message, error)
            ErrorSeverity.CRITICAL -> {
                Log.e(TAG, "💥 CRITICAL: $message", error)
                // Could trigger crash reporting here
            }
        }
    }
    
    /**
     * Get error statistics for debugging.
     */
    fun getErrorStats(): Map<String, Int> = errorCounts.toMap()
    
    /**
     * Clear error statistics.
     */
    fun clearErrorStats() {
        errorCounts.clear()
    }
    
    /**
     * Run a batch of operations and report aggregate results.
     */
    fun <T> batchExecute(
        operations: List<Pair<String, () -> T>>
    ): BatchResult<T> {
        val results = mutableListOf<SafeResult<T>>()
        var successCount = 0
        var failCount = 0
        
        operations.forEach { (name, op) ->
            val result = executeSafeSync(name, ErrorCategory.UNKNOWN, op)
            results.add(result)
            if (result.success) successCount++ else failCount++
        }
        
        return BatchResult(
            total = operations.size,
            successful = successCount,
            failed = failCount,
            results = results
        )
    }
    
    data class BatchResult<T>(
        val total: Int,
        val successful: Int,
        val failed: Int,
        val results: List<SafeResult<T>>
    ) {
        val successRate: Float get() = if (total > 0) successful.toFloat() / total else 0f
    }
}

/**
 * Extension function for easy error handling.
 */
inline fun <T> ErrorHandler.SafeResult<T>.onSuccess(action: (T) -> Unit): ErrorHandler.SafeResult<T> {
    if (success) data?.let(action)
    return this
}

inline fun ErrorHandler.SafeResult<*>.onFailure(action: (String) -> Unit): ErrorHandler.SafeResult<*> {
    if (!success) action(errorMessage)
    return this
}
