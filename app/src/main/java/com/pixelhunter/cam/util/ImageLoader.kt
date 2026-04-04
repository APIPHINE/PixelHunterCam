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

    private fun decodeBitmap(context: Context, path: String): Bitmap? {
        return try {
            if (path.startsWith("content://")) {
                val uri = Uri.parse(path)
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } else {
                BitmapFactory.decodeFile(path)
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
