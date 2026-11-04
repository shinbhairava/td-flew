package com.shinbhairava.tdflew.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object CacheTools {
    fun tdlibFilesDirectory(context: Context): File =
        File(context.filesDir, "tdlib/files")

    suspend fun cacheSize(context: Context): Long = withContext(Dispatchers.IO) {
        directorySize(context.cacheDir) + directorySize(tdlibFilesDirectory(context))
    }

    suspend fun clearLocalCaches(context: Context) = withContext(Dispatchers.IO) {
        context.cacheDir.listFiles()?.forEach { file ->
            runCatching { file.deleteRecursively() }
        }
        Unit
    }

    private fun directorySize(file: File): Long {
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::directorySize) ?: 0L
    }
}
