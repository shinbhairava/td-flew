package com.shinbhairava.tdflew.engine

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shinbhairava.tdflew.TdFlewApplication
import com.shinbhairava.tdflew.drive.DriveMetadata
import java.util.concurrent.TimeUnit

class AutoBackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? TdFlewApplication ?: return Result.failure()
        val container = app.container
        if (!container.settings.backupEnabled() || container.settings.backupUris().isEmpty()) {
            return Result.success()
        }
        if (container.telegram.driveChat.value == null) return Result.retry()

        return try {
            container.settings.backupUris().forEach { rawUri ->
                val root = DocumentFile.fromTreeUri(applicationContext, android.net.Uri.parse(rawUri))
                    ?: return@forEach
                val rootPath = DriveMetadata.childPath("/", root.name ?: "Backup") ?: "/"
                backupTree(container, root, rootPath)
            }
            Result.success()
        } catch (error: Throwable) {
            container.telegram.log("Automatic backup failed: ${friendlyError(error)}", LogLevel.WARN)
            Result.retry()
        }
    }

    private suspend fun backupTree(
        container: com.shinbhairava.tdflew.AppContainer,
        directory: DocumentFile,
        folderPath: String,
    ) {
        directory.listFiles().forEach { file ->
            if (file.isDirectory) {
                val child = DriveMetadata.childPath(folderPath, file.name ?: "Folder") ?: return@forEach
                backupTree(container, file, child)
            } else if (file.isFile) {
                val modified = file.lastModified()
                val size = file.length()
                val key = file.uri.toString()
                if (!container.settings.backupStamp(key, modified, size)) {
                    container.telegram.upload(
                        uri = file.uri,
                        folderPath = folderPath,
                        asMedia = false,
                        preferredName = file.name,
                        preferredMimeType = file.type,
                    )
                    container.settings.saveBackupStamp(key, modified, size)
                }
            }
        }
    }
}

object AutoBackupScheduler {
    private const val WORK_NAME = "td-flew-auto-backup"

    fun reschedule(context: Context) {
        val settings = (context.applicationContext as TdFlewApplication).container.settings
        val work = WorkManager.getInstance(context)
        if (!settings.backupEnabled() || settings.backupUris().isEmpty()) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
            settings.backupIntervalMinutes().coerceAtLeast(15L),
            TimeUnit.MINUTES,
        ).build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
