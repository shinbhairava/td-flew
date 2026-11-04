package com.shinbhairava.tdflew.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferStatus {
    RUNNING,
    COMPLETED,
    FAILED,
}

data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    val status: TransferStatus,
    val name: String,
    val mimeType: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val createdAt: Long,
    val completedAt: Long? = null,
    val fileId: Int = 0,
    val messageId: Long = 0L,
    val chatId: Long = 0L,
    val localUri: String? = null,
    val localPath: String? = null,
    val error: String? = null,
) {
    val progress: Float
        get() = if (bytesTotal > 0L) {
            (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

class TransferStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<TransferRecord>> = _records.asStateFlow()

    fun addDownload(
        fileId: Int,
        messageId: Long,
        chatId: Long,
        name: String,
        mimeType: String,
        bytesTotal: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        add(
            TransferRecord(
                id = id,
                direction = TransferDirection.DOWNLOAD,
                status = TransferStatus.RUNNING,
                name = name,
                mimeType = mimeType,
                bytesDone = 0L,
                bytesTotal = bytesTotal,
                createdAt = System.currentTimeMillis(),
                fileId = fileId,
                messageId = messageId,
                chatId = chatId,
            ),
        )
        return id
    }

    fun addUpload(name: String, mimeType: String, bytesTotal: Long): String {
        val id = UUID.randomUUID().toString()
        add(
            TransferRecord(
                id = id,
                direction = TransferDirection.UPLOAD,
                status = TransferStatus.RUNNING,
                name = name,
                mimeType = mimeType,
                bytesDone = 0L,
                bytesTotal = bytesTotal,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    private fun add(record: TransferRecord) {
        synchronized(lock) {
            _records.value = (listOf(record) + _records.value).take(MAX_RECORDS)
            persistLocked()
        }
    }

    fun updateProgressByFileId(fileId: Int, bytesDone: Long, bytesTotal: Long) {
        synchronized(lock) {
            val updated = _records.value.map { record ->
                if (record.fileId == fileId &&
                    record.direction == TransferDirection.DOWNLOAD &&
                    record.status == TransferStatus.RUNNING
                ) {
                    record.copy(
                        bytesDone = bytesDone.coerceAtLeast(0L),
                        bytesTotal = bytesTotal.takeIf { it > 0L } ?: record.bytesTotal,
                    )
                } else {
                    record
                }
            }
            _records.value = updated
        }
    }

    fun updateProgressById(id: String, bytesDone: Long, bytesTotal: Long) {
        synchronized(lock) {
            _records.value = _records.value.map { record ->
                if (record.id == id && record.status == TransferStatus.RUNNING) {
                    record.copy(
                        bytesDone = bytesDone.coerceAtLeast(0L),
                        bytesTotal = bytesTotal.takeIf { it > 0L } ?: record.bytesTotal,
                    )
                } else {
                    record
                }
            }
        }
    }

    fun completeByFileId(fileId: Int, localUri: String?, localPath: String?) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _records.value = _records.value.map { record ->
                if (record.fileId == fileId &&
                    record.direction == TransferDirection.DOWNLOAD &&
                    record.status == TransferStatus.RUNNING
                ) {
                    record.copy(
                        status = TransferStatus.COMPLETED,
                        bytesDone = record.bytesTotal,
                        completedAt = now,
                        localUri = localUri,
                        localPath = localPath,
                    )
                } else {
                    record
                }
            }
            persistLocked()
        }
    }

    fun failByFileId(fileId: Int, error: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _records.value = _records.value.map { record ->
                if (record.fileId == fileId &&
                    record.direction == TransferDirection.DOWNLOAD &&
                    record.status == TransferStatus.RUNNING
                ) {
                    record.copy(
                        status = TransferStatus.FAILED,
                        completedAt = now,
                        error = error,
                    )
                } else {
                    record
                }
            }
            persistLocked()
        }
    }

    fun completeUpload(id: String, fileId: Int) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _records.value = _records.value.map { record ->
                if (record.id == id && record.status == TransferStatus.RUNNING) {
                    record.copy(
                        status = TransferStatus.COMPLETED,
                        bytesDone = record.bytesTotal,
                        completedAt = now,
                        fileId = fileId,
                    )
                } else {
                    record
                }
            }
            persistLocked()
        }
    }

    fun failUpload(id: String, error: String) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            _records.value = _records.value.map { record ->
                if (record.id == id && record.status == TransferStatus.RUNNING) {
                    record.copy(
                        status = TransferStatus.FAILED,
                        completedAt = now,
                        error = error,
                    )
                } else {
                    record
                }
            }
            persistLocked()
        }
    }

    fun clearFinished() {
        synchronized(lock) {
            _records.value = _records.value.filter { it.status == TransferStatus.RUNNING }
            persistLocked()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            _records.value = emptyList()
            persistLocked()
        }
    }

    private fun loadRecords(): List<TransferRecord> {
        val serialized = preferences.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(serialized)
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val record = fromJson(json) ?: continue
                    add(
                        if (record.status == TransferStatus.RUNNING) {
                            record.copy(
                                status = TransferStatus.FAILED,
                                completedAt = System.currentTimeMillis(),
                                error = "Interrupted when the app closed",
                            )
                        } else {
                            record
                        },
                    )
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun persistLocked() {
        val array = JSONArray()
        _records.value.forEach { array.put(toJson(it)) }
        preferences.edit().putString(KEY_RECORDS, array.toString()).apply()
    }

    private fun toJson(record: TransferRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("direction", record.direction.name)
        put("status", record.status.name)
        put("name", record.name)
        put("mimeType", record.mimeType)
        put("bytesDone", record.bytesDone)
        put("bytesTotal", record.bytesTotal)
        put("createdAt", record.createdAt)
        put("completedAt", record.completedAt)
        put("fileId", record.fileId)
        put("messageId", record.messageId)
        put("chatId", record.chatId)
        put("localUri", record.localUri)
        put("localPath", record.localPath)
        put("error", record.error)
    }

    private fun fromJson(json: JSONObject): TransferRecord? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val direction = runCatching {
            TransferDirection.valueOf(json.optString("direction"))
        }.getOrNull() ?: return null
        val status = runCatching {
            TransferStatus.valueOf(json.optString("status"))
        }.getOrNull() ?: return null
        return TransferRecord(
            id = id,
            direction = direction,
            status = status,
            name = json.optString("name", "File"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            bytesDone = json.optLong("bytesDone", 0L),
            bytesTotal = json.optLong("bytesTotal", 0L),
            createdAt = json.optLong("createdAt", 0L),
            completedAt = json.optLong("completedAt", 0L).takeIf { it > 0L },
            fileId = json.optInt("fileId", 0),
            messageId = json.optLong("messageId", 0L),
            chatId = json.optLong("chatId", 0L),
            localUri = json.optString("localUri").takeIf { it.isNotBlank() },
            localPath = json.optString("localPath").takeIf { it.isNotBlank() },
            error = json.optString("error").takeIf { it.isNotBlank() },
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "tdflew_transfers"
        const val KEY_RECORDS = "records"
        const val MAX_RECORDS = 100
    }
}
