package com.shinbhairava.tdflew.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinbhairava.tdflew.AppContainer
import com.shinbhairava.tdflew.BuildConfig
import com.shinbhairava.tdflew.data.FileSaver
import com.shinbhairava.tdflew.data.TransferDirection
import com.shinbhairava.tdflew.data.TransferRecord
import com.shinbhairava.tdflew.data.TransferStatus
import com.shinbhairava.tdflew.drive.DriveChat
import com.shinbhairava.tdflew.engine.ConnectionStatus
import com.shinbhairava.tdflew.engine.LogEntry
import com.shinbhairava.tdflew.engine.LogLevel
import com.shinbhairava.tdflew.engine.TelegramManager
import com.shinbhairava.tdflew.engine.AutoBackupScheduler
import com.shinbhairava.tdflew.engine.friendlyError
import com.shinbhairava.tdflew.ui.components.ScreenHeader
import com.shinbhairava.tdflew.ui.components.TdAvatar
import com.shinbhairava.tdflew.ui.formatBytes
import com.shinbhairava.tdflew.ui.formatDateTime
import com.shinbhairava.tdflew.ui.formatShortDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MenuScreen(
    container: AppContainer,
    onMessage: (String) -> Unit,
    showBars: Boolean = true,
) {
    val manager = container.telegram
    val transfers = container.transfers
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drive by manager.driveChat.collectAsStateWithLifecycle()
    val profile by manager.profile.collectAsStateWithLifecycle()
    val connection by manager.connection.collectAsStateWithLifecycle()
    val transferRecords by transfers.records.collectAsStateWithLifecycle()
    val logs by manager.logs.collectAsStateWithLifecycle()

    var cacheSize by remember { mutableStateOf<Long?>(null) }
    var clearingCache by remember { mutableStateOf(false) }
    var resettingDrive by remember { mutableStateOf(false) }
    var showDrivePicker by remember { mutableStateOf(false) }
    var showTransfers by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    var showLogoutConfirm by remember { mutableStateOf(false) }
    var signingOut by remember { mutableStateOf(false) }
    var backupUris by remember { mutableStateOf(container.settings.backupUris()) }
    var backupEnabled by remember { mutableStateOf(container.settings.backupEnabled()) }
    var backupInterval by remember { mutableStateOf(container.settings.backupIntervalMinutes().toString()) }

    val backupFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            container.settings.addBackupUri(uri.toString())
            backupUris = container.settings.backupUris()
            AutoBackupScheduler.reschedule(context)
            onMessage("Backup folder added")
        }
    }

    suspend fun refreshCacheSize() {
        cacheSize = runCatching { manager.cacheSize() }.getOrDefault(0L)
    }

    LaunchedEffect(Unit) { refreshCacheSize() }

    val activeTransfers = transferRecords.filter { it.status == TransferStatus.RUNNING }
    val failedTransfers = transferRecords.filter { it.status == TransferStatus.FAILED }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                if (showBars) ScreenHeader(
                    title = "Menu",
                    subtitle = profile?.username?.let { "@$it" } ?: "Telegram drive controls",
                    actions = {
                        IconButton(onClick = { openUrl(context, BuildConfig.GITHUB_REPO_URL) }) {
                            Icon(Icons.Outlined.OpenInNew, contentDescription = "Open GitHub")
                        }
                    },
                )
            }

            item {
                ProfileCard(
                    manager = manager,
                    name = profile?.name ?: "Telegram user",
                    username = profile?.username,
                    photoFileId = profile?.photoFileId,
                    connection = connection,
                )
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.FolderShared,
                    title = "Telegram drive",
                    supporting = drive?.title ?: "No drive selected",
                ) {
                    if (drive != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = CircleShape,
                            ) {
                                Text(
                                    text = if (drive?.isSavedMessages == true) "Saved Messages" else "Chat ${drive?.id}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                text = "Outgoing files only",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showDrivePicker = true }) {
                            Text("Change drive")
                        }
                        OutlinedButton(
                            onClick = {
                                if (resettingDrive) return@OutlinedButton
                                resettingDrive = true
                                scope.launch {
                                    try {
                                        manager.setSavedMessagesDrive()
                                        onMessage("Drive reset to Saved Messages")
                                    } catch (throwable: Throwable) {
                                        onMessage(friendlyError(throwable))
                                    } finally {
                                        resettingDrive = false
                                    }
                                }
                            },
                            enabled = !resettingDrive,
                        ) {
                            Text(if (resettingDrive) "Resetting…" else "Saved Messages")
                        }
                    }
                }
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Automatic backup",
                    supporting = if (backupEnabled) {
                        "${backupUris.size} folder(s) · every ${backupInterval} minutes"
                    } else {
                        "Select Android folders to back up to Telegram"
                    },
                ) {
                    backupUris.forEach { raw ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = Uri.parse(raw).lastPathSegment?.substringAfterLast(':') ?: raw,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = {
                                container.settings.removeBackupUri(raw)
                                backupUris = container.settings.backupUris()
                                AutoBackupScheduler.reschedule(context)
                            }) { Text("Remove") }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = backupEnabled,
                            onCheckedChange = {
                                backupEnabled = it
                                container.settings.setBackupEnabled(it)
                                AutoBackupScheduler.reschedule(context)
                            },
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(if (backupEnabled) "Enabled" else "Disabled")
                    }
                    OutlinedTextField(
                        value = backupInterval,
                        onValueChange = { value ->
                            backupInterval = value.filter(Char::isDigit)
                            value.toLongOrNull()?.let {
                                container.settings.setBackupIntervalMinutes(it)
                                if (backupEnabled) AutoBackupScheduler.reschedule(context)
                            }
                        },
                        label = { Text("Interval (minutes, minimum 15)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { backupFolderPicker.launch(null) }) {
                            Icon(Icons.Outlined.FolderShared, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Add Android folder")
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 31) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                                }
                            }) { Text("Alarm permission") }
                        }
                    }
                }
            }
            item {
                MenuCard(
                    icon = Icons.Outlined.Storage,
                    title = "Storage & cache",
                    supporting = cacheSize?.let { "${formatBytes(it)} used by TD Flew" } ?: "Calculating…",
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { scope.launch { refreshCacheSize() } },
                            enabled = !clearingCache,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Recalculate")
                        }
                        Button(
                            onClick = {
                                if (clearingCache) return@Button
                                clearingCache = true
                                scope.launch {
                                    try {
                                        val remaining = manager.clearCache()
                                        cacheSize = remaining
                                        onMessage("Cache cleared")
                                    } catch (throwable: Throwable) {
                                        onMessage(friendlyError(throwable))
                                    } finally {
                                        clearingCache = false
                                    }
                                }
                            },
                            enabled = !clearingCache && activeTransfers.isEmpty(),
                        ) {
                            if (clearingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else {
                                Icon(Icons.Outlined.CleaningServices, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("Free cache")
                            }
                        }
                    }
                    if (activeTransfers.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Wait for active transfers before clearing the cache.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.Sync,
                    title = "Downloads & uploads",
                    supporting = when {
                        activeTransfers.size > 1 -> "${activeTransfers.size} transfers running"
                        activeTransfers.size == 1 -> activeTransfers.first().name
                        failedTransfers.isNotEmpty() -> "${failedTransfers.size} failed transfer(s)"
                        transferRecords.isNotEmpty() -> "${transferRecords.size} recent transfers"
                        else -> "No transfers yet"
                    },
                ) {
                    if (activeTransfers.isNotEmpty()) {
                        val latest = activeTransfers.first()
                        LinearProgressIndicator(
                            progress = { latest.progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showTransfers = true }) {
                            Icon(Icons.Outlined.Download, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Download manager")
                        }
                        if (transferRecords.isNotEmpty()) {
                            OutlinedButton(
                                onClick = {
                                    transfers.clearFinished()
                                    onMessage("Transfer history cleared")
                                },
                            ) {
                                Text("Clear finished")
                            }
                        }
                    }
                }
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.Article,
                    title = "Logs",
                    supporting = logs.firstOrNull()?.message ?: "No log entries yet",
                ) {
                    OutlinedButton(onClick = { showLogs = true }) {
                        Icon(Icons.Outlined.Article, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("View logs (${logs.size})")
                    }
                }
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.Info,
                    title = "About TD Flew",
                    supporting = "Version ${BuildConfig.VERSION_NAME} · ${BuildConfig.GITHUB_REPO_LABEL}",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { showAbout = true }) {
                            Text("About")
                        }
                        OutlinedButton(onClick = { showLicenses = true }) {
                            Text("Licenses")
                        }
                        OutlinedButton(onClick = { openUrl(context, BuildConfig.GITHUB_REPO_URL) }) {
                            Text("GitHub")
                        }
                    }
                }
            }

            item {
                MenuCard(
                    icon = Icons.Outlined.Logout,
                    title = "Telegram account",
                    supporting = profile?.name?.let { "$it · signed in with TDLib" } ?: "Signed in with TDLib",
                ) {
                    Button(
                        onClick = { showLogoutConfirm = true },
                        enabled = !signingOut,
                    ) {
                        if (signingOut) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(Icons.Outlined.Logout, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("Sign out of TDLib")
                        }
                    }
                }
            }
        }
    }

    if (showDrivePicker) {
        DrivePickerSheet(
            manager = manager,
            currentDrive = drive,
            onDismiss = { showDrivePicker = false },
            onSelected = { selected ->
                onMessage("Drive set to ${selected.title}")
                showDrivePicker = false
                resettingDrive = false
                scope.launch { refreshCacheSize() }
            },
            onMessage = onMessage,
        )
    }

    if (showTransfers) {
        TransferLogSheet(
            records = transferRecords,
            onDismiss = { showTransfers = false },
            onClearFinished = {
                transfers.clearFinished()
                onMessage("Finished transfers cleared")
            },
            onMessage = onMessage,
        )
    }

    if (showLogs) {
        LogsSheet(
            logs = logs,
            onDismiss = { showLogs = false },
            onMessage = onMessage,
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenGitHub = { openUrl(context, BuildConfig.GITHUB_REPO_URL) },
            onOpenLicenses = {
                showAbout = false
                showLicenses = true
            },
        )
    }

    if (showLicenses) {
        LicensesDialog(onDismiss = { showLicenses = false })
    }

    if (showLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { if (!signingOut) showLogoutConfirm = false },
            icon = { Icon(Icons.Outlined.Logout, contentDescription = null) },
            title = { Text("Sign out of Telegram?") },
            text = {
                Text(
                    "This ends the TDLib session on this device. Your messages and files stay safely in Telegram.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutConfirm = false
                        signingOut = true
                        scope.launch {
                            try {
                                manager.logout()
                            } catch (throwable: Throwable) {
                                onMessage(friendlyError(throwable))
                                signingOut = false
                            }
                        }
                    },
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    manager: TelegramManager,
    name: String,
    username: String?,
    photoFileId: Int?,
    connection: ConnectionStatus,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TdAvatar(
                manager = manager,
                fileId = photoFileId,
                name = name,
                modifier = Modifier.size(64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!username.isNullOrBlank()) {
                    Text(
                        text = "@$username",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val (label, color) = when (connection) {
                        ConnectionStatus.UP_TO_DATE -> "Connected" to MaterialTheme.colorScheme.primary
                        ConnectionStatus.CONNECTING -> "Connecting…" to MaterialTheme.colorScheme.tertiary
                        ConnectionStatus.UPDATING -> "Syncing…" to MaterialTheme.colorScheme.tertiary
                        ConnectionStatus.WAITING_FOR_NETWORK ->
                            "Waiting for network" to MaterialTheme.colorScheme.error
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(color, CircleShape),
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuCard(
    icon: ImageVector,
    title: String,
    supporting: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.size(42.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun DrivePickerSheet(
    manager: TelegramManager,
    currentDrive: DriveChat?,
    onDismiss: () -> Unit,
    onSelected: (DriveChat) -> Unit,
    onMessage: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<DriveChat>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var manualInput by rememberSaveable { mutableStateOf("") }
    var busyChatId by remember { mutableStateOf<Long?>(null) }
    var busySaved by remember { mutableStateOf(false) }
    var busyManual by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(query) {
        delay(300)
        loading = true
        error = null
        try {
            results = manager.searchChats(query)
        } catch (throwable: Throwable) {
            error = friendlyError(throwable)
        } finally {
            loading = false
        }
    }

    fun chooseSavedMessages() {
        if (busySaved || busyChatId != null || busyManual) return
        busySaved = true
        scope.launch {
            try {
                onSelected(manager.setSavedMessagesDrive())
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            } finally {
                busySaved = false
            }
        }
    }

    fun chooseChat(chat: DriveChat) {
        if (busyChatId != null || busySaved || busyManual) return
        busyChatId = chat.id
        scope.launch {
            try {
                onSelected(manager.setDriveChat(chat.id))
            } catch (throwable: Throwable) {
                onMessage(friendlyError(throwable))
            } finally {
                busyChatId = null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 16.dp),
        ) {
            Text(
                text = "Choose your drive",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "TD Flew stores its file index as messages in this chat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("Search your chats") },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (currentDrive != null && !currentDrive.isSavedMessages) {
                    ChatRow(
                        title = currentDrive.title,
                        subtitle = "Pinned drive · Chat ${currentDrive.id}",
                        selected = true,
                        icon = Icons.Outlined.Bookmark,
                        busy = false,
                        onClick = { onSelected(currentDrive) },
                    )
                }
                ChatRow(
                    title = "Saved Messages",
                    subtitle = "Private, synced to your own account",
                    selected = currentDrive?.isSavedMessages == true,
                    icon = Icons.Outlined.Bookmark,
                    busy = busySaved,
                    onClick = { chooseSavedMessages() },
                )
                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text("Searching chats…", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (results.isEmpty()) {
                    Text(
                        text = if (query.isBlank()) {
                            "No chats found. Use a chat ID or link below."
                        } else {
                            "No chats match \"$query\"."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    )
                } else {
                    results.forEach { chat ->
                        ChatRow(
                            title = chat.title,
                            subtitle = if (chat.isSavedMessages) "Saved Messages" else "Chat ${chat.id}",
                            selected = currentDrive?.id == chat.id,
                            icon = Icons.Outlined.Cloud,
                            busy = busyChatId == chat.id,
                            onClick = { chooseChat(chat) },
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(14.dp))
            Text(
                text = "Or use a chat ID, @username, or t.me link",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manualInput,
                onValueChange = {
                    manualInput = it
                    manualError = null
                },
                placeholder = { Text("https://t.me/mychannel") },
                isError = manualError != null,
                supportingText = if (manualError != null) {
                    { Text(manualError.orEmpty()) }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (busyManual) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.size(10.dp))
                }
                Button(
                    onClick = {
                        if (busyManual || manualInput.isBlank()) return@Button
                        busyManual = true
                        manualError = null
                        scope.launch {
                            try {
                                onSelected(manager.setDriveByInput(manualInput))
                            } catch (throwable: Throwable) {
                                manualError = friendlyError(throwable)
                            } finally {
                                busyManual = false
                            }
                        }
                    },
                    enabled = !busyManual && manualInput.isNotBlank() && busyChatId == null && !busySaved,
                ) {
                    Text("Use this chat")
                }
            }
        }
    }
}

@Composable
private fun ChatRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: ImageVector,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            shape = CircleShape,
            modifier = Modifier.size(42.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text(
                text = "Current",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun TransferLogSheet(
    records: List<TransferRecord>,
    onDismiss: () -> Unit,
    onClearFinished: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Download manager", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${records.size} recent transfers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClearFinished) {
                    Text("Clear finished")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (records.isEmpty()) {
                Text(
                    text = "Uploads and downloads will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 520.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(records, key = { it.id }) { record ->
                        TransferRow(
                            record = record,
                            onOpen = {
                                val uri = record.localUri
                                if (uri != null && FileSaver.open(context, uri, record.mimeType)) {
                                    onMessage("Opening ${record.name}")
                                } else {
                                    onMessage("That file is no longer available in Downloads.")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(record: TransferRecord, onOpen: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (record.direction == TransferDirection.DOWNLOAD) {
                        Icons.Outlined.Download
                    } else {
                        Icons.Outlined.CloudUpload
                    },
                    contentDescription = null,
                    tint = when (record.status) {
                        TransferStatus.RUNNING -> MaterialTheme.colorScheme.primary
                        TransferStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
                        TransferStatus.FAILED -> MaterialTheme.colorScheme.error
                    },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = when (record.status) {
                            TransferStatus.RUNNING -> {
                                val percent = (record.progress * 100).toInt()
                                "Running · $percent%"
                            }

                            TransferStatus.COMPLETED -> "Completed · ${formatShortDate(record.createdAt)}"
                            TransferStatus.FAILED -> "Failed · ${record.error ?: "Unknown error"}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (record.status == TransferStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.status == TransferStatus.COMPLETED &&
                    record.direction == TransferDirection.DOWNLOAD &&
                    record.localUri != null
                ) {
                    TextButton(onClick = onOpen) { Text("Open") }
                }
            }
            if (record.status == TransferStatus.RUNNING) {
                Spacer(Modifier.height(8.dp))
                if (record.progress > 0f) {
                    LinearProgressIndicator(
                        progress = { record.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun LogsSheet(
    logs: List<LogEntry>,
    onDismiss: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Logs", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = "${logs.size} entries · newest first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        val text = logs.joinToString("\n") { entry ->
                            "${formatDateTime(entry.timestamp)} [${entry.level.name}] ${entry.message}"
                        }
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TD Flew logs", text))
                        onMessage("Logs copied")
                    },
                    enabled = logs.isNotEmpty(),
                ) {
                    Text("Copy")
                }
            }
            Spacer(Modifier.height(8.dp))
            if (logs.isEmpty()) {
                Text(
                    text = "TDLib activity and app events will appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 560.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(logs) { entry ->
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = formatDateTime(entry.timestamp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Text(
                                    text = entry.level.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = when (entry.level) {
                                        LogLevel.INFO -> MaterialTheme.colorScheme.primary
                                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                                    },
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                            Text(
                                text = entry.message,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
    onOpenLicenses: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = CircleShape,
                modifier = Modifier.size(62.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        },
        title = { Text("TD Flew") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A Telegram storage and backup app built with TDLib and Material 3 Expressive.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Files are stored as messages in the Telegram chat you choose. " +
                        "The app never sees your Telegram password. TDLib talks to Telegram directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenGitHub) {
                Icon(Icons.Outlined.OpenInNew, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("GitHub")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onOpenLicenses) { Text("Licenses") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}

@Composable
private fun LicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        runCatching {
            context.assets.open("THIRD_PARTY_LICENSES.txt")
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("Third-party license text is unavailable.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open-source licenses") },
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
