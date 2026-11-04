package com.shinbhairava.tdflew.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

data class MediaInfo(
    val width: Int,
    val height: Int,
    val durationSeconds: Int,
    val thumbnail: File?,
)

object MediaTools {
    private const val THUMBNAIL_MAX = 320

    fun inspect(file: File, mimeType: String, cacheDir: File): MediaInfo {
        return when {
            mimeType.startsWith("image/") -> inspectImage(file, cacheDir)
            mimeType.startsWith("video/") -> inspectVideo(file, cacheDir)
            else -> MediaInfo(0, 0, 0, null)
        }
    }

    fun guessMimeType(fileName: String, fallback: String = "application/octet-stream"): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isBlank()) return fallback
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: fallback
    }

    private fun inspectImage(file: File, cacheDir: File): MediaInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return MediaInfo(0, 0, 0, null)
        }

        val rotation = runCatching {
            val exif = ExifInterface(file.absolutePath)
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, THUMBNAIL_MAX)
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: return MediaInfo(bounds.outWidth, bounds.outHeight, 0, null)

        val transformed = applyRotation(decoded, rotation)
        if (transformed !== decoded) decoded.recycle()
        val thumbnail = saveThumbnail(transformed, cacheDir, file.name)
        transformed.recycle()

        val rotated = rotation == ExifInterface.ORIENTATION_ROTATE_90 ||
            rotation == ExifInterface.ORIENTATION_ROTATE_270 ||
            rotation == ExifInterface.ORIENTATION_TRANSPOSE ||
            rotation == ExifInterface.ORIENTATION_TRANSVERSE
        val width = if (rotated) bounds.outHeight else bounds.outWidth
        val height = if (rotated) bounds.outWidth else bounds.outHeight
        return MediaInfo(width, height, 0, thumbnail)
    }

    private fun inspectVideo(file: File, cacheDir: File): MediaInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val durationSeconds = (durationMs / 1000L).toInt().coerceIn(0, 86_400)

            val rotated = rotation == 90 || rotation == 270
            val width = if (rotated) rawHeight else rawWidth
            val height = if (rotated) rawWidth else rawHeight

            val frame = runCatching {
                retriever.getFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
            }.getOrNull()
            val thumbnail = frame?.let {
                val rotatedFrame = applyRotation(it, rotation)
                val result = saveThumbnail(rotatedFrame, cacheDir, file.name)
                if (rotatedFrame !== it) rotatedFrame.recycle()
                it.recycle()
                result
            }
            MediaInfo(width, height, durationSeconds, thumbnail)
        } catch (_: RuntimeException) {
            MediaInfo(0, 0, 0, null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxSize: Int): Int {
        var sample = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > maxSize * 2 || currentHeight > maxSize * 2) {
            sample *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return sample
    }

    private fun applyRotation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }

            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun saveThumbnail(source: Bitmap, cacheDir: File, seed: String): File? {
        if (source.width <= 0 || source.height <= 0) return null
        val longest = max(source.width, source.height)
        val scaled = if (longest > THUMBNAIL_MAX) {
            val ratio = THUMBNAIL_MAX.toFloat() / longest.toFloat()
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            source
        }

        val directory = File(cacheDir, "thumbnails").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$seed-${System.nanoTime()}".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        val output = File(directory, "thumb_$digest.jpg")

        return try {
            FileOutputStream(output).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 82, stream)
            }
            output
        } catch (_: RuntimeException) {
            output.delete()
            null
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
