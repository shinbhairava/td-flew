package com.shinbhairava.tdflew.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SavedFile(
    val uri: String,
    val displayPath: String,
)

object FileSaver {
    suspend fun save(
        context: Context,
        source: File,
        displayName: String,
        mimeType: String,
    ): SavedFile = withContext(Dispatchers.IO) {
        require(source.exists()) { "The downloaded file no longer exists" }
        val safeName = sanitizeFileName(displayName.ifBlank { source.name })
        val mime = mimeType.ifBlank { "application/octet-stream" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(context, source, safeName, mime)
        } else {
            saveToAppDownloads(context, source, safeName)
        }
    }

    fun open(context: Context, uri: String, mimeType: String): Boolean {
        if (uri.isBlank()) return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(uri), mimeType.ifBlank { "application/octet-stream" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun share(context: Context, uri: String, mimeType: String, title: String): Boolean {
        if (uri.isBlank()) return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeType.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(send, "Share ${title.ifBlank { "file" }}").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    fun shareMultiple(context: Context, files: List<SavedFile>, mimeTypes: List<String>): Boolean {
        if (files.isEmpty()) return false
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = if (mimeTypes.all { it.startsWith("image/") }) "image/*" else "*/*"
            putParcelableArrayListExtra(
                Intent.EXTRA_STREAM,
                ArrayList(files.map { Uri.parse(it.uri) }),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(send, "Share files"))
            true
        }.getOrDefault(false)
    }

    fun sanitizeFileName(raw: String): String {
        val cleaned = raw
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()
            .trim('.')
            .replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "download" }.take(160)
    }

    private fun saveToMediaStore(
        context: Context,
        source: File,
        name: String,
        mimeType: String,
    ): SavedFile {
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/TD Flew"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values)
            ?: error("Could not create a file in Downloads")

        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            } ?: error("Could not open the destination file")

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return SavedFile(
                uri = uri.toString(),
                displayPath = "$relativePath/$name",
            )
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToAppDownloads(
        context: Context,
        source: File,
        name: String,
    ): SavedFile {
        val downloadsRoot = File(
            context.getExternalFilesDir(null) ?: context.filesDir,
            "Downloads/TD Flew",
        ).apply { mkdirs() }

        val destination = uniqueFile(downloadsRoot, name)
        try {
            source.inputStream().use { input ->
                FileOutputStream(destination).use { output ->
                    input.copyTo(output, DEFAULT_BUFFER_SIZE)
                }
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                destination,
            )
            return SavedFile(
                uri = uri.toString(),
                displayPath = destination.absolutePath,
            )
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index += 1
        }
        return candidate
    }
}
