package com.pixelhunter.cam.util

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Safe MediaStore operations with comprehensive error handling.
 * 
 * Wraps all file I/O operations to prevent crashes and provide
 * graceful degradation when storage is unavailable.
 */
object SafeMediaStore {
    
    private const val TAG = "SafeMediaStore"
    private const val MAX_RETRIES = 3
    private const val RETRY_DELAY_MS = 100L
    
    data class SaveOperation(
        val fileName: String,
        val mimeType: String,
        val relativePath: String,
        val content: suspend (OutputStream) -> Unit
    )
    
    data class SaveResult(
        val success: Boolean,
        val uri: Uri? = null,
        val filePath: String? = null,
        val error: String? = null
    )
    
    /**
     * Safely save an image to MediaStore with retry logic.
     */
    suspend fun saveImage(
        context: Context,
        fileName: String,
        relativePath: String = Environment.DIRECTORY_PICTURES + "/PixelHunter",
        bitmap: Bitmap? = null,
        content: (suspend (OutputStream) -> Unit)? = null
    ): SaveResult {
        return ErrorHandler.executeSafe(
            operationName = "saveImage($fileName)",
            category = ErrorHandler.ErrorCategory.STORAGE,
            maxAttempts = MAX_RETRIES
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveImageApi29Plus(context, fileName, relativePath, bitmap, content)
            } else {
                saveImageLegacy(context, fileName, relativePath, bitmap, content)
            }
        }.let { result ->
            SaveResult(
                success = result.success,
                uri = result.data?.first,
                filePath = result.data?.second,
                error = result.errorMessage.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveImageApi29Plus(
        context: Context,
        fileName: String,
        relativePath: String,
        bitmap: Bitmap?,
        content: (suspend (OutputStream) -> Unit)?
    ): Pair<Uri, String> {
        val resolver = context.contentResolver
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create MediaStore entry")
            
            resolver.openOutputStream(uri)?.use { outputStream ->
                if (bitmap != null) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
                } else if (content != null) {
                    content(outputStream)
                } else {
                    throw IllegalArgumentException("Either bitmap or content must be provided")
                }
            } ?: throw IOException("Failed to open output stream")
            
            // Mark as complete
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
            
            val path = getPathFromUri(context, uri) ?: uri.toString()
            Log.i(TAG, "✅ Saved image: $path")
            return Pair(uri, path)
            
        } catch (e: Exception) {
            // Clean up partial entry
            uri?.let { resolver.delete(it, null, null) }
            throw e
        }
    }
    
    private suspend fun saveImageLegacy(
        context: Context,
        fileName: String,
        relativePath: String,
        bitmap: Bitmap?,
        content: (suspend (OutputStream) -> Unit)?
    ): Pair<Uri, String> {
        val baseDir = when {
            relativePath.contains("Pictures") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            relativePath.contains("Download") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        }
        
        val folderName = relativePath.substringAfterLast("/", "PixelHunter")
        val targetDir = File(baseDir, folderName)
        
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                throw IOException("Failed to create directory: ${targetDir.absolutePath}")
            }
        }
        
        val targetFile = File(targetDir, fileName)
        
        FileOutputStream(targetFile).use { outputStream ->
            if (bitmap != null) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            } else if (content != null) {
                content(outputStream)
            } else {
                throw IllegalArgumentException("Either bitmap or content must be provided")
            }
        }
        
        // Trigger media scan
        val uri = Uri.fromFile(targetFile)
        Log.i(TAG, "✅ Saved image (legacy): ${targetFile.absolutePath}")
        return Pair(uri, targetFile.absolutePath)
    }
    
    /**
     * Safely save JSON content to Downloads.
     */
    suspend fun saveJson(
        context: Context,
        fileName: String,
        content: String,
        relativePath: String = Environment.DIRECTORY_DOWNLOADS + "/PixelHunter"
    ): SaveResult {
        return ErrorHandler.executeSafe(
            operationName = "saveJson($fileName)",
            category = ErrorHandler.ErrorCategory.STORAGE,
            maxAttempts = MAX_RETRIES
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveJsonApi29Plus(context, fileName, content, relativePath)
            } else {
                saveJsonLegacy(context, fileName, content, relativePath)
            }
        }.let { result ->
            SaveResult(
                success = result.success,
                uri = result.data?.first?.let { Uri.parse(it) },
                filePath = result.data?.second,
                error = result.errorMessage.takeIf { it.isNotEmpty() }
            )
        }
    }
    
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun saveJsonApi29Plus(
        context: Context,
        fileName: String,
        content: String,
        relativePath: String
    ): Pair<String, String> {
        val resolver = context.contentResolver
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
        }
        
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IOException("Failed to create JSON MediaStore entry")
        
        resolver.openOutputStream(uri)?.use { outputStream ->
            outputStream.write(content.toByteArray())
        } ?: throw IOException("Failed to open output stream for JSON")
        
        val path = uri.toString()
        Log.i(TAG, "✅ Saved JSON: $path")
        return Pair(path, path)
    }
    
    private suspend fun saveJsonLegacy(
        context: Context,
        fileName: String,
        content: String,
        relativePath: String
    ): Pair<String, String> {
        val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folderName = relativePath.substringAfterLast("/", "PixelHunter")
        val targetDir = File(baseDir, folderName)
        
        if (!targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                throw IOException("Failed to create directory: ${targetDir.absolutePath}")
            }
        }
        
        val targetFile = File(targetDir, fileName)
        targetFile.writeText(content)
        
        Log.i(TAG, "✅ Saved JSON (legacy): ${targetFile.absolutePath}")
        return Pair(targetFile.toURI().toString(), targetFile.absolutePath)
    }
    
    /**
     * Safely write EXIF data to an existing image.
     */
    fun writeExifSafe(
        context: Context,
        imageUri: Uri,
        writeOperation: (androidx.exifinterface.media.ExifInterface) -> Unit
    ): Boolean {
        return try {
            val parcelFileDescriptor = context.contentResolver.openFileDescriptor(imageUri, "rw")
                ?: return false
            
            parcelFileDescriptor.use { pfd ->
                androidx.exifinterface.media.ExifInterface(pfd.fileDescriptor).apply {
                    writeOperation(this)
                    saveAttributes()
                }
            }
            Log.d(TAG, "✅ EXIF written to $imageUri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to write EXIF to $imageUri: ${e.message}")
            false
        }
    }
    
    /**
     * Check if storage is available and writable.
     */
    fun isStorageAvailable(): Boolean {
        val state = Environment.getExternalStorageState()
        return Environment.MEDIA_MOUNTED == state
    }
    
    /**
     * Get available storage space in MB.
     */
    fun getAvailableStorageMb(): Long {
        return try {
            val stat = android.os.StatFs(Environment.getExternalStorageDirectory().path)
            val blockSize = stat.blockSizeLong
            val availableBlocks = stat.availableBlocksLong
            (availableBlocks * blockSize) / (1024 * 1024)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage stats: ${e.message}")
            0
        }
    }
    
    /**
     * Check if there's enough space for an operation.
     */
    fun hasEnoughSpace(requiredMb: Long = 50): Boolean {
        val available = getAvailableStorageMb()
        return available >= requiredMb
    }
    
    /**
     * Get path from URI (best effort).
     */
    fun getPathFromUri(context: Context, uri: Uri): String? {
        return when (uri.scheme) {
            "file" -> uri.path
            "content" -> {
                // Try to get path from content URI
                val projection = arrayOf(MediaStore.Images.Media.DATA)
                try {
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                            cursor.getString(columnIndex)
                        } else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not get path from URI: $uri")
                    null
                }
            }
            else -> uri.toString()
        }
    }
    
    /**
     * Delete a file safely.
     */
    fun deleteFile(context: Context, uri: Uri): Boolean {
        return try {
            val deleted = context.contentResolver.delete(uri, null, null) > 0
            if (deleted) {
                Log.d(TAG, "🗑️ Deleted: $uri")
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to delete $uri: ${e.message}")
            false
        }
    }
    
    /**
     * Verify a saved file exists and has content.
     */
    fun verifyFile(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                Log.d(TAG, "✅ Verified file: $uri ($size bytes)")
                size > 0
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to verify $uri: ${e.message}")
            false
        }
    }
    
    /**
     * Comprehensive pre-flight check before save operation.
     */
    fun preFlightCheck(context: Context, requiredSpaceMb: Long = 50): PreFlightResult {
        val issues = mutableListOf<String>()
        
        // Check storage availability
        if (!isStorageAvailable()) {
            issues.add("Storage not available")
            return PreFlightResult(false, issues)
        }
        
        // Check space
        val availableMb = getAvailableStorageMb()
        if (availableMb < requiredSpaceMb) {
            issues.add("Insufficient space: ${availableMb}MB available, ${requiredSpaceMb}MB required")
        }
        
        // Check low space warning
        if (availableMb < 100) {
            issues.add("Storage is critically low (${availableMb}MB)")
        }
        
        return PreFlightResult(issues.isEmpty(), issues)
    }
    
    data class PreFlightResult(
        val canProceed: Boolean,
        val issues: List<String>
    )
}

/**
 * Extension to safely use ContentResolver.
 */
inline fun <T> ContentResolver.useQuery(
    uri: android.net.Uri,
    projection: Array<String>?,
    selection: String? = null,
    selectionArgs: Array<String>? = null,
    sortOrder: String? = null,
    block: (android.database.Cursor) -> T
): T? {
    return try {
        query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            block(cursor)
        }
    } catch (e: Exception) {
        Log.e("ContentResolver", "Query failed: ${e.message}")
        null
    }
}
