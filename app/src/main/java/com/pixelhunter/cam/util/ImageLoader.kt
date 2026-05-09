package com.pixelhunter.cam.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Safe image loader that handles:
 * - Content URIs (MediaStore) and file paths
 * - EXIF orientation correction
 * - Graceful fallbacks
 */
object ImageLoader {

    /**
     * Load a bitmap from a path (file path or content:// URI string) with EXIF orientation applied.
     */
    fun loadBitmap(context: Context, path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null

        val bitmap = decodeBitmap(context, path) ?: return null
        val orientation = readExifOrientation(context, path)
        return applyExifOrientation(bitmap, orientation)
    }

    /**
     * Load a bitmap scaled to fit within [maxDimension] on its largest side,
     * with EXIF orientation applied. Prevents OOM when displaying large captures.
     */
    fun loadBitmap(context: Context, path: String?, maxDimension: Int): Bitmap? {
        if (path.isNullOrBlank()) return null

        val orientation = readExifOrientation(context, path)
        val needsRotation = orientation == ExifInterface.ORIENTATION_ROTATE_90 ||
                orientation == ExifInterface.ORIENTATION_ROTATE_270

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        decodeBitmap(context, path, options)

        val srcWidth = options.outWidth
        val srcHeight = options.outHeight
        if (srcWidth <= 0 || srcHeight <= 0) return null

        // Account for rotation when calculating sample size
        val (w, h) = if (needsRotation) srcHeight to srcWidth else srcWidth to srcHeight
        options.inSampleSize = calculateInSampleSize(w, h, maxDimension, maxDimension)
        options.inJustDecodeBounds = false

        val bitmap = decodeBitmap(context, path, options) ?: return null
        return applyExifOrientation(bitmap, orientation)
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        while (width / inSampleSize >= reqWidth * 2 && height / inSampleSize >= reqHeight * 2) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /**
     * Load a bitmap from a file with EXIF orientation applied.
     */
    fun loadBitmap(file: File): Bitmap? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val orientation = try {
            ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        return applyExifOrientation(bitmap, orientation)
    }

    /**
     * Apply EXIF orientation to an already-decoded bitmap.
     * Returns the original bitmap if no rotation is needed.
     * Caller is responsible for recycling the original bitmap if a new one is returned.
     */
    fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }

        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    private fun decodeBitmap(context: Context, path: String, options: BitmapFactory.Options? = null): Bitmap? {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, options ?: BitmapFactory.Options())
                }
            } else {
                BitmapFactory.decodeFile(path, options ?: BitmapFactory.Options())
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun readExifOrientation(context: Context, path: String): Int {
        return try {
            when {
                path.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(path))?.use {
                        ExifInterface(it).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    } ?: ExifInterface.ORIENTATION_NORMAL
                }
                else -> {
                    ExifInterface(path).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
    }
}
