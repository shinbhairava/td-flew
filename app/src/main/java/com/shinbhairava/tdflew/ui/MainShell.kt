package com.shinbhairava.tdflew.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shinbhairava.tdflew.AppContainer
import com.shinbhairava.tdflew.data.TransferRecord
import com.shinbhairava.tdflew.data.TransferStatus
import com.shinbhairava.tdflew.ui.files.FilesScreen
import com.shinbhairava.tdflew.ui.gallery.GalleryScreen
import com.shinbhairava.tdflew.ui.menu.MenuScreen
import kotlinx.coroutines.launch

private enum class MainTab(
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
) {
    GALLERY("Gallery", Icons.Outlined.PhotoLibrary, Icons.Rounded.PhotoLibrary),
    FILES("Files", Icons.Outlined.FolderOpen, Icons.Rounded.Folder),
    MENU("Menu", Icons.Outlined.Tune, Icons.Rounded.Tune),
}

@Composable
fun MainShell(container: AppContainer) {
    val manager = container.telegram
    val drive by manager.driveChat.collectAsStateWithLifecycle()
    val transferRecords by container.transfers.records.collectAsStateWithLifecycle()
    var selectedTabName by rememberSaveable { mutableStateOf(MainTab.GALLERY.name) }
    var barsVisible by rememberSaveable { mutableStateOf(true) }
    val scrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -2f) barsVisible = false
                if (available.y > 2f) barsVisible = true
                return Offset.Zero
            }
        }
    }
    val selectedTab = MainTab.entries.firstOrNull { it.name == selectedTabName } ?: MainTab.GALLERY
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activeTransfers = transferRecords.filter { it.status == TransferStatus.RUNNING }

    val onMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AnimatedVisibility(
                visible = barsVisible,
                enter = slideInVertically(animationSpec = tween(420)) { it } + fadeIn(tween(300)),
                exit = slideOutVertically(animationSpec = tween(420)) { it } + fadeOut(tween(300)),
            ) {
                Column {
                    if (activeTransfers.isNotEmpty()) {
                        ActiveTransferStrip(records = activeTransfers)
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(332.dp)
                                .height(64.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            tonalElevation = 3.dp,
                        ) {
                            ShortNavigationBar(
                                modifier = Modifier.fillMaxWidth(),
                                containerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ) {
                                MainTab.entries.forEach { tab ->
                                    val selected = tab == selectedTab
                                    ShortNavigationBarItem(
                                        selected = selected,
                                        onClick = { selectedTabName = tab.name },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) tab.selectedIcon else tab.icon,
                                                contentDescription = tab.label,
                                            )
                                        },
                                        label = { Text(tab.label) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "main-tab",
            modifier = Modifier.padding(padding).nestedScroll(scrollConnection),
        ) { tab ->
            when (tab) {
                MainTab.GALLERY -> GalleryScreen(
                    manager = manager,
                    onMessage = onMessage,
                    onOpenMenu = { selectedTabName = MainTab.MENU.name },
                    showBars = barsVisible,
                )

                MainTab.FILES -> FilesScreen(
                    manager = manager,
                    onMessage = onMessage,
                    onOpenMenu = { selectedTabName = MainTab.MENU.name },
                    showBars = barsVisible,
                )

                MainTab.MENU -> MenuScreen(
                    container = container,
                    onMessage = onMessage,
                    showBars = barsVisible,
                )
            }
        }
    }
}

@Composable
private fun ActiveTransferStrip(records: List<TransferRecord>) {
    val latest = records.firstOrNull() ?: return
    val countLabel = if (records.size > 1) " · ${records.size} active" else ""
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val progress = latest.progress
                if (progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                }
                Text(
                    text = "${latest.name} · ${(latest.progress * 100).toInt()}%$countLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(2f),
                )
            }
        }
    }
}
