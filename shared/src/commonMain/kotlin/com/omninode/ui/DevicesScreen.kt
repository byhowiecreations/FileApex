package com.omninode.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TabletAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omninode.data.identity.LocalIdentity
import com.omninode.presentation.BrowseTarget
import com.omninode.presentation.DeviceListRow
import com.omninode.presentation.DevicesViewModel
import com.omninode.ui.adaptive.OmniPaneSectionHeader
import com.omninode.ui.dnd.deviceFileDropTarget
import com.omninode.ui.theme.OmniTeal
import com.omninode.ui.theme.OmniTealDark
import kotlinx.coroutines.flow.distinctUntilChanged

/** Approximate device card row height used for desktop window sizing. */
val DeviceCardSlotHeight = 96.dp

/** Empty space under the last device card (~2 card rows), inside the list section. */
val DeviceListToAddGap = DeviceCardSlotHeight * 2

enum class HomeTab {
    Devices,
    Files,
    Settings
}

enum class DevicesScreenLayoutMode {
    /** Phone / folded: full scaffold with bottom bar. */
    FullScreen,
    /** Tablet / unfolded list pane (~35%). */
    ListPane
}

private data class PendingDelete(
    val deviceId: String,
    val deviceName: String
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(
    onOpenDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onScanQr: () -> Unit,
    onOpenSettings: () -> Unit,
    onExitApp: () -> Unit,
    viewModel: DevicesViewModel = viewModel { DevicesViewModel() },
    layoutMode: DevicesScreenLayoutMode = DevicesScreenLayoutMode.FullScreen,
    selectedDeviceId: String? = null
) {
    val state by viewModel.uiState.collectAsState()
    val deviceRows by viewModel.deviceRows.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var addMenuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var renameText by remember { mutableStateOf("") }
    var pinText by remember { mutableStateOf("") }
    var confirmExit by remember { mutableStateOf(false) }
    val isListPane = layoutMode == DevicesScreenLayoutMode.ListPane

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = viewModel.initialListScrollIndex(),
        initialFirstVisibleItemScrollOffset = viewModel.initialListScrollOffset()
    )
    val currentOnOpenDevice by rememberUpdatedState(onOpenDevice)

    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.saveListScroll(index, offset)
            }
    }

    LaunchedEffect(state.statusMessage, state.errorMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessages()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (!isListPane) {
                HomeTopBar(onExitClick = { confirmExit = true })
            }
        },
        bottomBar = {
            if (!isListPane) {
                OmniBottomBar(
                    selected = HomeTab.Devices,
                    onDevices = {},
                    onFiles = onOpenLocalFiles,
                    onSettings = onOpenSettings
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isListPane) {
                OmniPaneSectionHeader(title = "Paired Devices")
            }
            PairedDevicesList(
                listState = listState,
                localDeviceName = state.localDeviceName,
                deviceRows = deviceRows,
                selectedDeviceId = selectedDeviceId,
                onOpenLocal = { currentOnOpenDevice(viewModel.thisDeviceTarget()) },
                onRenameLocal = {
                    renameText = state.localDeviceName
                    viewModel.beginRename(LocalIdentity.LOCAL_DEVICE_ID)
                },
                onOpenDevice = { deviceId ->
                    viewModel.openDeviceOrExplain(deviceId) { target ->
                        currentOnOpenDevice(target)
                    }
                },
                onRenameDevice = { deviceId, deviceName ->
                    renameText = deviceName
                    viewModel.beginRename(deviceId)
                },
                onRemoveDevice = { deviceId, deviceName ->
                    pendingDelete = PendingDelete(deviceId, deviceName)
                },
                onFilesDropped = { deviceId, paths ->
                    viewModel.sendDroppedLocalFiles(deviceId, paths)
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            // Always pinned above bottom navigation — not overlapping the list.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 10.dp)
            ) {
                Button(
                    onClick = { addMenuOpen = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OmniTeal,
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Add New Device",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { addMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Generate QR Code") },
                        onClick = {
                            addMenuOpen = false
                            onGenerateQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Scan QR Code") },
                        onClick = {
                            addMenuOpen = false
                            onScanQr()
                        }
                    )
                }
            }
        }
    }

    val renameId = state.renameTargetId
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = viewModel::cancelRename,
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmRename(renameId, renameText) }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRename) { Text("Cancel") }
            }
        )
    }

    state.pendingPinPairing?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinPairing()
            },
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "Enter the PIN for ${pending.deviceName} to finish pairing.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinPairing(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text("Pair")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinPairing()
                    }
                ) { Text("Cancel") }
            }
        )
    }

    state.pendingPinUnlock?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                pinText = ""
                viewModel.cancelPinUnlock()
            },
            title = { Text("Enter device PIN") },
            text = {
                Column {
                    Text(
                        text = "Enter the PIN for ${pending.displayName} to browse files.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = pinText,
                        onValueChange = { pinText = it.filter { ch -> ch.isDigit() }.take(8) },
                        singleLine = true,
                        label = { Text("PIN") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.confirmPinUnlock(pinText)
                        pinText = ""
                    },
                    enabled = pinText.isNotBlank()
                ) {
                    Text("Unlock")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pinText = ""
                        viewModel.cancelPinUnlock()
                    }
                ) { Text("Cancel") }
            }
        )
    }

    pendingDelete?.let { device ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Remove device?") },
            text = { Text("Remove ${device.deviceName} from paired devices?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDevice(device.deviceId)
                        pendingDelete = null
                    }
                ) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Exit OmniNode?") },
            text = { Text("Stop sharing and close the app.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        onExitApp()
                    }
                ) { Text("Exit") }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) { Text("Cancel") }
            }
        )
    }
}

/**
 * Diff-keyed LazyColumn for paired devices.
 *
 * Compose equivalent of ListAdapter + DiffUtil + disabled SimpleItemAnimator:
 * - [items] key = [DeviceListRow.deviceId] (areItemsTheSame)
 * - row data class equality drives cell invalidation (areContentsTheSame)
 * - [Modifier.animateItem] with null specs disables placement/change animations
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PairedDevicesList(
    listState: LazyListState,
    localDeviceName: String,
    deviceRows: List<DeviceListRow>,
    selectedDeviceId: String?,
    onOpenLocal: () -> Unit,
    onRenameLocal: () -> Unit,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            start = 20.dp,
            end = 20.dp,
            top = 8.dp,
            bottom = DeviceListToAddGap
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(
            key = "this-device",
            contentType = "local-device"
        ) {
            DeviceCard(
                title = "This device ($localDeviceName)",
                subtitle = "Online · Local files",
                icon = Icons.Filled.PhoneAndroid,
                selected = selectedDeviceId == LocalIdentity.LOCAL_DEVICE_ID,
                onClick = onOpenLocal,
                onRename = onRenameLocal,
                onRemove = null,
                dropDeviceId = LocalIdentity.LOCAL_DEVICE_ID,
                onFilesDropped = onFilesDropped,
                modifier = Modifier.animateItem(
                    fadeInSpec = null,
                    fadeOutSpec = null,
                    placementSpec = null
                )
            )
        }

        if (deviceRows.isEmpty()) {
            item(
                key = "empty",
                contentType = "empty"
            ) {
                Text(
                    text = "No paired devices yet. Tap Add New Device to generate or scan a QR code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = null
                        )
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
        } else {
            items(
                items = deviceRows,
                key = { it.deviceId },
                contentType = { "paired-device" }
            ) { row ->
                DeviceCard(
                    title = row.title,
                    subtitle = row.subtitle,
                    icon = deviceIconFor(row.deviceName),
                    selected = selectedDeviceId == row.deviceId,
                    onClick = { onOpenDevice(row.deviceId) },
                    onRename = { onRenameDevice(row.deviceId, row.deviceName) },
                    onRemove = { onRemoveDevice(row.deviceId, row.deviceName) },
                    dropDeviceId = row.deviceId,
                    onFilesDropped = onFilesDropped,
                    modifier = Modifier.animateItem(
                        fadeInSpec = null,
                        fadeOutSpec = null,
                        placementSpec = null
                    )
                )
            }
        }
    }
}

@Composable
private fun HomeTopBar(onExitClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(OmniTeal)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(
                onClick = onExitClick,
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = "Exit OmniNode",
                    tint = Color.White
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Paired Devices",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "OmniNode",
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = (-0.5).sp
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun OmniBottomBar(
    selected: HomeTab,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    NavigationBar(
        containerColor = OmniTeal,
        contentColor = Color.White,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == HomeTab.Devices,
            onClick = onDevices,
            icon = {
                NavIcon(
                    selected = selected == HomeTab.Devices,
                    imageVector = Icons.Filled.Devices,
                    contentDescription = "Devices"
                )
            },
            label = {
                Text(
                    "Devices",
                    color = if (selected == HomeTab.Devices) Color.White else Color.White.copy(alpha = 0.85f)
                )
            },
            colors = navItemColors()
        )
        NavigationBarItem(
            selected = selected == HomeTab.Files,
            onClick = onFiles,
            icon = {
                NavIcon(
                    selected = selected == HomeTab.Files,
                    imageVector = Icons.Filled.Folder,
                    contentDescription = "Local Files"
                )
            },
            label = {
                Text(
                    "Local Files",
                    color = if (selected == HomeTab.Files) Color.White else Color.White.copy(alpha = 0.85f)
                )
            },
            colors = navItemColors()
        )
        NavigationBarItem(
            selected = selected == HomeTab.Settings,
            onClick = onSettings,
            icon = {
                NavIcon(
                    selected = selected == HomeTab.Settings,
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Settings"
                )
            },
            label = {
                Text(
                    "Settings",
                    color = if (selected == HomeTab.Settings) Color.White else Color.White.copy(alpha = 0.85f)
                )
            },
            colors = navItemColors()
        )
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = OmniTealDark,
    unselectedIconColor = Color.White,
    selectedTextColor = Color.White,
    unselectedTextColor = Color.White.copy(alpha = 0.85f),
    indicatorColor = Color.White
)

@Composable
private fun NavIcon(
    selected: Boolean,
    imageVector: ImageVector,
    contentDescription: String
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color.White else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (selected) OmniTealDark else Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun DeviceCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    dropDeviceId: String? = null,
    onFilesDropped: ((deviceId: String, paths: List<String>) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    val dropTargetId = dropDeviceId
    val dropCallback = onFilesDropped
    val dropModifier = if (dropTargetId != null && dropCallback != null) {
        Modifier.deviceFileDropTarget(
            enabled = true,
            onHoverChange = { dropHover = it },
            onFilesDropped = { paths -> dropCallback(dropTargetId, paths) }
        )
    } else {
        Modifier
    }
    val highlighted = selected || dropHover
    val containerColor = when {
        dropHover -> OmniTeal.copy(alpha = 0.22f)
        selected -> OmniTeal.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(dropModifier)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (highlighted) 0.dp else 3.dp),
        border = BorderStroke(
            width = if (highlighted) 2.dp else 1.5.dp,
            color = if (highlighted) OmniTeal else OmniTeal.copy(alpha = 0.55f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = OmniTealDark,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = OmniTeal,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(22.dp)
                )
            }
            if (onRename != null || onRemove != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = "Device options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text("Rename") },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun deviceIconFor(name: String): ImageVector {
    val lower = name.lowercase()
    return when {
        "macbook" in lower || "laptop" in lower || "desktop" in lower || "pc" in lower ->
            Icons.Filled.Computer
        "ipad" in lower || "tablet" in lower -> Icons.Filled.TabletAndroid
        else -> Icons.Filled.PhoneAndroid
    }
}
