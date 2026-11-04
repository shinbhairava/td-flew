package com.shinbhairava.tdflew.drive

enum class DriveItemKind {
    FOLDER,
    PHOTO,
    VIDEO,
    FILE;

    val isMedia: Boolean
        get() = this == PHOTO || this == VIDEO
}

data class DriveChat(
    val id: Long,
    val title: String,
    val isSavedMessages: Boolean,
)

data class DriveItem(
    val id: String,
    val messageId: Long,
    val path: String,
    val parentPath: String,
    val name: String,
    val kind: DriveItemKind,
    val size: Long,
    val timestamp: Long,
    val mimeType: String,
    val fileId: Int? = null,
    val thumbnailFileId: Int? = null,
    val durationSeconds: Int = 0,
    val isManaged: Boolean = true,
    val chunkGroup: String? = null,
    val chunkIndex: Int = 0,
    val chunkTotal: Int = 0,
    val chunkParts: List<DriveChunkPart> = emptyList(),
) {
    val isFolder: Boolean
        get() = kind == DriveItemKind.FOLDER
}

data class DriveChunkPart(
    val messageId: Long,
    val fileId: Int,
    val size: Long,
    val index: Int,
)

data class DriveSnapshot(
    val folders: List<DriveItem>,
    val files: List<DriveItem>,
    val media: List<DriveItem>,
    val scannedMessages: Int,
    val truncated: Boolean,
) {
    val documents: List<DriveItem>
        get() = folders + files

    val allManagedItems: List<DriveItem>
        get() = folders + files + media

    companion object {
        val Empty = DriveSnapshot(
            folders = emptyList(),
            files = emptyList(),
            media = emptyList(),
            scannedMessages = 0,
            truncated = false,
        )
    }
}

enum class SortField {
    NAME,
    DATE,
    SIZE,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}
