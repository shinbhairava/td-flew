package com.shinbhairava.tdflew.ui.gallery

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinbhairava.tdflew.drive.DriveItem
import com.shinbhairava.tdflew.drive.DriveItemKind
import com.shinbhairava.tdflew.drive.DriveSnapshot
import com.shinbhairava.tdflew.engine.GalleryPreset
import com.shinbhairava.tdflew.engine.GalleryQuery
import com.shinbhairava.tdflew.engine.TelegramManager
import com.shinbhairava.tdflew.engine.filterGallery
import com.shinbhairava.tdflew.engine.friendlyError
import com.shinbhairava.tdflew.ui.components.EmptyState
import com.shinbhairava.tdflew.ui.components.LoadingState
import com.shinbhairava.tdflew.ui.components.ScreenHeader
import com.shinbhairava.tdflew.ui.components.TdImage
import com.shinbhairava.tdflew.ui.components.TdVideo
import com.shinbhairava.tdflew.ui.dayKey
import com.shinbhairava.tdflew.ui.fileSubtitle
import com.shinbhairava.tdflew.ui.formatDateTime
import com.shinbhairava.tdflew.ui.formatShortDate
import com.shinbhairava.tdflew.ui.friendlyDayLabel
import kotlinx.coroutines.launch

@Composable
fun GalleryScreen(
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
    var search by rememberSaveable { mutableStateOf("") }
    var presetName by rememberSaveable { mutableStateOf(GalleryPreset.ALL.name) }
    var viewerItem by remember { mutableStateOf<DriveItem?>(null) }
    var deleteTarget by remember { mutableStateOf<DriveItem?>(null) }
    var uploadCount by remember { mutableIntStateOf(0) }
    var uploading by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf<String?>(null) }
    var uploadTags by rememberSaveable { mutableStateOf("") }

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

    val media = snapshot?.media.orEmpty()
    val parsedQuery = remember(search) { GalleryQuery.parse(search) }
    val preset = GalleryPreset.entries.firstOrNull { it.name == presetName } ?: GalleryPreset.ALL
    val filtered = remember(media, parsedQuery, preset) {
        filterGallery(media, parsedQuery, preset)
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(50),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            var success = 0
            var failure: String? = null
            uris.forEach { uri ->
                try {
                    manager.upload(
                        uri = uri,
                        folderPath = "/",
                        // Send the original as a Telegram document rather than
                        // compressing it into photo/video media. TD Flew still
                        // indexes image/video documents in Gallery and uses
                        // their Telegram thumbnails for previews.
                        asMedia = false,
                        tags = uploadTags.split(Regex("[ ,]+")),
                    )
                    success += 1
                    uploadCount += 1
                } catch (throwable: Throwable) {
                    if (failure == null) failure = friendlyError(throwable)
                }
            }
            uploading = false
            refreshToken += 1
            onMessage(
                when {
                    failure != null && success > 0 -> "Uploaded $success item(s). $failure"
                    failure != null -> failure ?: "Upload failed"
                    success == 1 -> "Uploaded 1 item"
                    else -> "Uploaded $success items"
                },
            )
        }
    }

    fun launchPicker() {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
        )
    }

    fun saveItem(item: DriveItem) {
        actionBusy = "Saving…"
        scope.launch {
            try {
                val saved = manager.download(item)
                onMessage("Saved to ${saved.displayPath}")
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            } finally {
                actionBusy = null
            }
        }
    }

    fun openItem(item: DriveItem) {
        actionBusy = "Opening…"
        scope.launch {
            try {
                manager.open(item)
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            } finally {
                actionBusy = null
            }
        }
    }

    fun shareItem(item: DriveItem) {
        actionBusy = "Preparing…"
        scope.launch {
            try {
                manager.share(item)
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            } finally {
                actionBusy = null
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "Gallery",
                subtitle = drive?.let { "${it.title} · ${media.size} photos & videos" },
                actions = {
                    FilledTonalIconButton(
                        onClick = { refreshToken += 1 },
                        enabled = !loading,
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Refresh gallery")
                    }
                },
            )

            if (drive == null) {
                EmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = "Choose a Telegram drive",
                    message = "Pick a chat in Menu and your photos and videos will appear here.",
                    actionLabel = "Open Menu",
                    onAction = onOpenMenu,
                    modifier = Modifier.weight(1f),
                )
                return@Column
            }

            if (loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (snapshot?.truncated == true) {
                TruncatedBanner()
            }

            SearchAndFilters(
                search = search,
                onSearchChange = { search = it },
                preset = preset,
                onPresetChange = { presetName = it.name },
                resultCount = filtered.size,
                tags = uploadTags,
                onTagsChange = { uploadTags = it },
            )

            when {
                error != null -> EmptyState(
                    icon = Icons.Outlined.Image,
                    title = "Couldn't load the gallery",
                    message = error.orEmpty(),
                    actionLabel = "Try again",
                    onAction = { refreshToken += 1 },
                    modifier = Modifier.weight(1f),
                )

                snapshot == null && loading -> LoadingState(
                    label = "Reading your Telegram gallery…",
                    modifier = Modifier.weight(1f),
                )

                filtered.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Image,
                    title = if (search.isBlank() && preset == GalleryPreset.ALL) {
                        "No photos yet"
                    } else {
                        "No matching photos"
                    },
                    message = if (search.isBlank() && preset == GalleryPreset.ALL) {
                        "Upload photos and videos, or send them to your drive chat in Telegram."
                    } else {
                        "Try another name, date tag, or date range."
                    },
                    actionLabel = if (search.isBlank() && preset == GalleryPreset.ALL) "Upload" else null,
                    onAction = if (search.isBlank() && preset == GalleryPreset.ALL) {
                        { launchPicker() }
                    } else {
                        null
                    },
                    modifier = Modifier.weight(1f),
                )

                else -> MediaGrid(
                    media = filtered,
                    manager = manager,
                    onOpen = { viewerItem = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { launchPicker() },
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
            text = {
                Text(
                    when {
                        uploading -> "Uploading…"
                        uploadCount > 0 -> "Upload more"
                        else -> "Upload"
                    },
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        )
    }

    viewerItem?.let { item ->
        MediaViewer(
            items = filtered,
            initialPage = filtered.indexOfFirst { it.id == item.id }.coerceAtLeast(0),
            manager = manager,
            actionBusy = actionBusy,
            onClose = { if (actionBusy == null) viewerItem = null },
            onSave = { saveItem(it) },
            onOpen = { openItem(it) },
            onShare = { shareItem(it) },
            onDelete = { selected ->
                viewerItem = null
                deleteTarget = selected
            },
        )
    }

    deleteTarget?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text("Delete from Telegram?") },
            text = {
                Text(
                    "This removes \"${item.name}\" from your drive chat for everyone. " +
                        "Files already saved to Downloads are not affected.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            try {
                                manager.deleteItem(item)
                                onMessage("Deleted ${item.name}")
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
private fun TruncatedBanner() {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = "Showing the most recent 5,000 messages. Older media may not be listed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun SearchAndFilters(
    search: String,
    onSearchChange: (String) -> Unit,
    preset: GalleryPreset,
    onPresetChange: (GalleryPreset) -> Unit,
    resultCount: Int,
    tags: String,
    onTagsChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = search,
            onValueChange = onSearchChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Search name or date: 2026-11-04") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (search.isNotBlank()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            shape = CircleShape,
        )
        OutlinedTextField(
            value = tags,
            onValueChange = onTagsChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text("Tags for new uploads: car, home, tree") },
            leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GalleryPreset.entries.forEach { entry ->
                FilterChip(
                    selected = preset == entry,
                    onClick = { onPresetChange(entry) },
                    label = { Text(entry.label) },
                    leadingIcon = if (entry == GalleryPreset.ALL) {
                        null
                    } else {
                        { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) }
                    },
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "$resultCount shown",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MediaGrid(
    media: List<DriveItem>,
    manager: TelegramManager,
    onOpen: (DriveItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = remember(media) {
        media.groupBy { dayKey(it.timestamp) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 104.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        grouped.forEach { (_, items) ->
            item(
                key = "day-${items.first().timestamp}",
                span = { GridItemSpan(maxLineSpan) },
            ) {
                DayHeader(item = items.first(), count = items.size)
            }
            items(items = items, key = { it.id }) { item ->
                MediaTile(item = item, manager = manager, onClick = { onOpen(item) })
            }
        }
    }
}

@Composable
private fun DayHeader(item: DriveItem, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = friendlyDayLabel(item.timestamp),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "$count items",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MediaTile(
    item: DriveItem,
    manager: TelegramManager,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        TdImage(
            manager = manager,
            fileId = item.thumbnailFileId ?: item.fileId,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            placeholderIcon = if (item.kind == DriveItemKind.VIDEO) {
                Icons.Outlined.Videocam
            } else {
                Icons.Outlined.Image
            },
        )

        if (item.kind == DriveItemKind.VIDEO) {
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.PlayArrow,
                        contentDescription = "Video",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            if (item.durationSeconds > 0) {
                Surface(
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                ) {
                    Text(
                        text = formatDuration(item.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.68f)),
                    ),
                )
                .padding(start = 9.dp, end = 9.dp, top = 18.dp, bottom = 8.dp),
        ) {
            Text(
                text = formatShortDate(item.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun MediaViewer(
    items: List<DriveItem>,
    initialPage: Int,
    manager: TelegramManager,
    actionBusy: String?,
    onClose: () -> Unit,
    onSave: (DriveItem) -> Unit,
    onOpen: (DriveItem) -> Unit,
    onShare: (DriveItem) -> Unit,
    onDelete: (DriveItem) -> Unit,
) {
    if (items.isEmpty()) return
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val pagerState = rememberPagerState(
            initialPage = initialPage.coerceIn(0, items.lastIndex),
            pageCount = { items.size },
        )
        val item = items[pagerState.currentPage]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val pageItem = items[page]
                if (pageItem.kind == DriveItemKind.VIDEO) {
                    TdVideo(
                        manager = manager,
                        fileId = pageItem.fileId,
                        contentDescription = pageItem.name,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    TdImage(
                        manager = manager,
                        fileId = pageItem.fileId ?: pageItem.thumbnailFileId,
                        contentDescription = pageItem.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        placeholderIcon = Icons.Outlined.Image,
                        priority = 1,
                    )
                }
            }

            if (item.kind == DriveItemKind.VIDEO) {
                Surface(
                    color = Color.White.copy(alpha = 0.18f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(76.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.PlayArrow,
                            contentDescription = "Play video",
                            tint = Color.White,
                            modifier = Modifier
                                .size(52.dp)
                            .clickable { onOpen(item) },
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalIconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Clear, contentDescription = "Close")
                }
                Spacer(Modifier.weight(1f))
                FilledTonalIconButton(onClick = { onSave(item) }, enabled = actionBusy == null) {
                    Icon(Icons.Outlined.Download, contentDescription = "Save to Downloads")
                }
                FilledTonalIconButton(onClick = { onShare(item) }, enabled = actionBusy == null) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share")
                }
                FilledTonalIconButton(onClick = { onDelete(item) }, enabled = actionBusy == null) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete from Telegram")
                }
            }

            Surface(
                color = Color.Black.copy(alpha = 0.68f),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${fileSubtitle(item.mimeType, item.size)} · ${formatDateTime(item.timestamp)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.76f),
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (item.kind == DriveItemKind.VIDEO) {
                            TextButton(onClick = { onOpen(item) }, enabled = actionBusy == null) {
                                Icon(
                                    Icons.Outlined.OpenInNew,
                                    contentDescription = null,
                                    tint = Color.White,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Play video", color = Color.White)
                            }
                        }
                        TextButton(onClick = { onSave(item) }, enabled = actionBusy == null) {
                            Icon(
                                Icons.Outlined.Download,
                                contentDescription = null,
                                tint = Color.White,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Save copy", color = Color.White)
                        }
                        if (actionBusy != null) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                            Text(
                                text = actionBusy,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
