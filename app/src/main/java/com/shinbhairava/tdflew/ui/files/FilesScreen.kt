package com.shinbhairava.tdflew.ui.files

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinbhairava.tdflew.drive.DriveItem
import com.shinbhairava.tdflew.drive.DriveMetadata
import com.shinbhairava.tdflew.drive.DriveSnapshot
import com.shinbhairava.tdflew.drive.SortDirection
import com.shinbhairava.tdflew.drive.SortField
import com.shinbhairava.tdflew.engine.TelegramManager
import com.shinbhairava.tdflew.engine.friendlyError
import com.shinbhairava.tdflew.ui.ScreenHeader
import com.shinbhairava.tdflew.ui.components.EmptyState
import com.shinbhairava.tdflew.ui.components.LoadingState
import com.shinbhairava.tdflew.ui.components.TdImage
import com.shinbhairava.tdflew.ui.fileSubtitle
import com.shinbhairava.tdflew.ui.formatShortDate
import kotlinx.coroutines.launch

@Composable
fun FilesScreen(
    manager: TelegramManager,
    onMessage: (String) -> Unit,
    onOpenMenu: () -> Unit,
) {
    val drive by manager.driveChat.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var snapshot by remember(drive?.id) { mutableStateOf<DriveSnapshot?>(null) }
    var loading by remember(drive?.id) { mutableStateOf(false) }
    var error by remember(drive?.id) { mutableStateOf<String?>(null) }
    var refreshToken by remember(drive?.id) { mutableIntStateOf(0) }
    var currentPath by rememberSaveable(drive?.id) { mutableStateOf("/") }
    var sortFieldName by rememberSaveable { mutableStateOf(SortField.NAME.name) }
    var descending by rememberSaveable { mutableStateOf(false) }
    var actionItem by remember { mutableStateOf<DriveItem?>(null) }
    var deleteTarget by remember { mutableStateOf<DriveItem?>(null) }
    var showCreateFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var folderError by remember { mutableStateOf<String?>(null) }
    var creatingFolder by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var uploadTargetPath by remember { mutableStateOf("/") }

    val sortField = SortField.entries.firstOrNull { it.name == sortFieldName } ?: SortField.NAME
    val direction = if (descending) SortDirection.DESCENDING else SortDirection.ASCENDING

    LaunchedEffect(drive?.id, refreshToken) {
        val currentDrive = drive ?: return@LaunchedEffect
        loading = snapshot == null
        error = null
        try {
            snapshot = manager.snapshot(currentDrive.id, force = refreshToken > 0)
        } catch (throwable: Throwable) {
            error = friendlyError(throwable)
        } finally {
            loading = false
        }
    }

    LaunchedEffect(snapshot, currentPath) {
        val current = snapshot ?: return@LaunchedEffect
        if (currentPath != "/" && current.folders.none { it.path == currentPath }) {
            currentPath = "/"
        }
    }

    val folders = snapshot?.folders.orEmpty()
        .filter { it.parentPath == currentPath }
        .sortedFor(sortField, direction)
    val files = snapshot?.files.orEmpty()
        .filter { it.parentPath == currentPath }
        .sortedFor(sortField, direction)
    val listItems = folders + files

    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val target = uploadTargetPath
        uploading = true
        scope.launch {
            var success = 0
            var failure: String? = null
            uris.forEach { uri ->
                try {
                    manager.upload(
                        uri = uri,
                        folderPath = target,
                        asMedia = false,
                    )
                    success += 1
                } catch (throwable: Throwable) {
                    if (failure == null) failure = friendlyError(throwable)
                }
            }
            uploading = false
            refreshToken += 1
            onMessage(
                when {
                    failure != null && success > 0 -> "Uploaded $success file(s). $failure"
                    failure != null -> failure ?: "Upload failed"
                    success == 1 -> "Uploaded 1 file"
                    else -> "Uploaded $success files"
                },
            )
        }
    }

    fun launchUpload() {
        uploadTargetPath = currentPath
        uploadLauncher.launch(arrayOf("*/*"))
    }

    fun runAction(label: String, action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            }
        }
    }

    fun openItem(item: DriveItem) {
        if (item.isFolder) {
            currentPath = item.path
        } else {
            actionItem = item
        }
    }

    BackHandler(enabled = currentPath != "/") {
        currentPath = DriveMetadata.parentOf(currentPath)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = if (currentPath == "/") "Files" else DriveMetadata.nameOf(currentPath),
                subtitle = drive?.let {
                    "${it.title} · ${folders.size} folders · ${files.size} files"
                },
                onBack = if (currentPath != "/") {
                    { currentPath = DriveMetadata.parentOf(currentPath) }
                } else {
                    null
                },
                actions = {
                    FilledTonalIconButton(
                        onClick = { refreshToken += 1 },
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh files")
                    }
                    if (currentPath == "/" || snapshot?.folders?.any { it.path == currentPath } == true) {
                        FilledTonalIconButton(
                            onClick = {
                                folderName = ""
                                folderError = null
                                showCreateFolder = true
                            },
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder")
                        }
                    }
                },
            )

            if (drive == null) {
                EmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = "Choose a Telegram drive",
                    message = "Pick a chat in Menu to start using it as a file manager.",
                    actionLabel = "Open Menu",
                    onAction = onOpenMenu,
                    modifier = Modifier.weight(1f),
                )
                return@Column
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Breadcrumbs(
                path = currentPath,
                onNavigate = { currentPath = it },
            )

            SortControls(
                field = sortField,
                descending = descending,
                onFieldChange = { sortFieldName = it.name },
                onToggleDirection = { descending = !descending },
            )

            when {
                error != null -> EmptyState(
                    icon = Icons.Outlined.InsertDriveFile,
                    title = "Couldn't load your files",
                    message = error.orEmpty(),
                    actionLabel = "Try again",
                    onAction = { refreshToken += 1 },
                    modifier = Modifier.weight(1f),
                )

                snapshot == null && loading -> LoadingState(
                    label = "Reading your Telegram drive…",
                    modifier = Modifier.weight(1f),
                )

                listItems.isEmpty() -> EmptyState(
                    icon = if (currentPath == "/") Icons.Outlined.InsertDriveFile else Icons.Outlined.Folder,
                    title = "This folder is empty",
                    message = "Upload files or create a folder to keep your Telegram drive organised.",
                    actionLabel = "Upload files",
                    onAction = { launchUpload() },
                    modifier = Modifier.weight(1f),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 104.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = listItems, key = { it.id }) { item ->
                        FileRow(
                            item = item,
                            manager = manager,
                            childCount = if (item.isFolder) {
                                snapshot?.allManagedItems
                                    ?.count { it.path.startsWith("${item.path}/") }
                                    ?: 0
                            } else {
                                0
                            },
                            onClick = { openItem(item) },
                            onMore = { actionItem = item },
                        )
                    }
                }
            }

            if (snapshot?.truncated == true) {
                Text(
                    text = "Showing the most recent 5,000 messages.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { launchUpload() },
            icon = {
                if (uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                }
            },
            text = { Text(if (uploading) "Uploading…" else "Upload files") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }

    actionItem?.let { item ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { actionItem = null },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
            ) {
                Column(Modifier.padding(horizontal = 24.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (item.isFolder) {
                            "Folder · ${item.path}"
                        } else {
                            "${fileSubtitle(item.mimeType, item.size)} · ${formatShortDate(item.timestamp)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (item.isFolder) {
                    SheetAction(
                        icon = Icons.Outlined.Folder,
                        label = "Open folder",
                        onClick = {
                            actionItem = null
                            currentPath = item.path
                        },
                    )
                    SheetAction(
                        icon = Icons.Outlined.DeleteOutline,
                        label = "Delete folder and contents",
                        destructive = true,
                        onClick = {
                            actionItem = null
                            deleteTarget = item
                        },
                    )
                } else {
                    SheetAction(
                        icon = Icons.Outlined.OpenInNew,
                        label = "Open",
                        onClick = {
                            actionItem = null
                            runAction("Opening") { manager.open(item) }
                        },
                    )
                    SheetAction(
                        icon = Icons.Outlined.Download,
                        label = "Save to Downloads",
                        onClick = {
                            actionItem = null
                            scope.launch {
                                try {
                                    val saved = manager.download(item)
                                    onMessage("Saved to ${saved.displayPath}")
                                } catch (throwable: Throwable) {
                                    onMessage(friendlyError(throwable))
                                }
                            }
                        },
                    )
                    SheetAction(
                        icon = Icons.Outlined.Share,
                        label = "Share",
                        onClick = {
                            actionItem = null
                            runAction("Sharing") { manager.share(item) }
                        },
                    )
                    SheetAction(
                        icon = Icons.Outlined.DeleteOutline,
                        label = "Delete from Telegram",
                        destructive = true,
                        onClick = {
                            actionItem = null
                            deleteTarget = item
                        },
                    )
                }
            }
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { if (!creatingFolder) showCreateFolder = false },
            icon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
            title = { Text("New folder") },
            text = {
                Column {
                    OutlinedTextField(
                        value = folderName,
                        onValueChange = {
                            folderName = it
                            folderError = null
                        },
                        label = { Text("Folder name") },
                        singleLine = true,
                        isError = folderError != null,
                        supportingText = if (folderError != null) {
                            { Text(folderError.orEmpty()) }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = DriveMetadata.sanitizeFolderName(folderName)
                        if (name == null) {
                            folderError = "Enter a valid folder name."
                            return@Button
                        }
                        val duplicate = folders.any { it.name.equals(name, ignoreCase = true) }
                        if (duplicate) {
                            folderError = "A folder with that name already exists."
                            return@Button
                        }
                        creatingFolder = true
                        scope.launch {
                            try {
                                manager.createFolder(currentPath, name)
                                onMessage("Created folder $name")
                                showCreateFolder = false
                                folderName = ""
                                refreshToken += 1
                            } catch (throwable: Throwable) {
                                folderError = friendlyError(throwable)
                            } finally {
                                creatingFolder = false
                            }
                        }
                    },
                    enabled = !creatingFolder && folderName.isNotBlank(),
                ) {
                    if (creatingFolder) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("Create")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showCreateFolder = false },
                    enabled = !creatingFolder,
                ) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(if (item.isFolder) "Delete folder?" else "Delete from Telegram?") },
            text = {
                Text(
                    if (item.isFolder) {
                        "This permanently removes \"${item.name}\" and everything inside it from the drive chat."
                    } else {
                        "This removes \"${item.name}\" from the drive chat for everyone. Saved copies are not affected."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            try {
                                val count = manager.deleteItem(item)
                                onMessage("Deleted $count item(s)")
                                refreshToken += 1
                            } catch (throwable: Throwable) {
                                onMessage(friendlyError(throwable))
                            }
                        }
                    },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val segments = path.trim('/').split('/').filter { it.isNotBlank() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onNavigate("/") },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text("Root")
        }
        var accumulated = ""
        segments.forEach { segment ->
            accumulated = "$accumulated/$segment"
            val target = accumulated
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = { onNavigate(target) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(segment, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SortControls(
    field: SortField,
    descending: Boolean,
    onFieldChange: (SortField) -> Unit,
    onToggleDirection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Sort",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SortField.entries.forEach { entry ->
            FilterChip(
                selected = field == entry,
                onClick = { onFieldChange(entry) },
                label = { Text(entry.label()) },
            )
        }
        FilledTonalIconButton(
            onClick = onToggleDirection,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = if (descending) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward,
                contentDescription = if (descending) "Descending" else "Ascending",
            )
        }
        Text(
            text = "Folders first",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    item: DriveItem,
    manager: TelegramManager,
    childCount: Int,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.isFolder) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            } else if (item.thumbnailFileId != null) {
                TdImage(
                    manager = manager,
                    fileId = item.thumbnailFileId,
                    contentDescription = item.name,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.medium),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = iconForMime(item.mimeType),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (item.isFolder) {
                        "Folder · $childCount item(s)"
                    } else {
                        "${fileSubtitle(item.mimeType, item.size)} · ${formatShortDate(item.timestamp)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "More actions")
            }
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (destructive) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

private fun List<DriveItem>.sortedFor(field: SortField, direction: SortDirection): List<DriveItem> {
    val comparator = when (field) {
        SortField.NAME -> compareBy<DriveItem> { it.name.lowercase() }
        SortField.DATE -> compareBy { it.timestamp }
        SortField.SIZE -> compareBy { it.size }
    }
    val sorted = sortedWith(comparator)
    return if (direction == SortDirection.DESCENDING) sorted.reversed() else sorted
}

private fun SortField.label(): String = when (this) {
    SortField.NAME -> "Name"
    SortField.DATE -> "Date"
    SortField.SIZE -> "Size"
}

private fun iconForMime(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Outlined.Image
    mimeType.startsWith("video/") -> Icons.Outlined.Videocam
    mimeType.startsWith("audio/") -> Icons.Outlined.Audiotrack
    mimeType.contains("pdf") -> Icons.Outlined.PictureAsPdf
    mimeType.contains("zip") || mimeType.contains("compressed") -> Icons.Outlined.FolderZip
    mimeType.startsWith("text/") || mimeType.contains("json") || mimeType.contains("xml") ->
        Icons.Outlined.Description

    else -> Icons.Outlined.InsertDriveFile
}
