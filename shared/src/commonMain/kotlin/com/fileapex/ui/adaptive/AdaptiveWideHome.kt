package com.fileapex.ui.adaptive

import com.fileapex.i18n.stringRes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TableRows
import androidx.compose.material.icons.filled.ViewColumn
import com.fileapex.ui.FileApexIcons
import com.fileapex.data.settings.FreestyleLayoutMode
import com.fileapex.di.FileApexServices
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.domain.transfer.TransferActivityGuard
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.DevicesScreen
import com.fileapex.ui.DevicesScreenLayoutMode
import com.fileapex.ui.NoteHeaderButton
import com.fileapex.ui.ExplorerViewModeToggle
import com.fileapex.ui.LiveTransferBanner
import com.fileapex.ui.QueuedFilesButton
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.HomeTab
import com.fileapex.ui.SettingsScreen
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.platform.BackgroundPersistenceUiState
import com.fileapex.ui.devicesNavLabel
import com.fileapex.ui.isMainHomeScreen
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.fileApexChromeBottomEdge
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.ui.theme.fileApexNavSelectedBackgroundColor
import com.fileapex.ui.theme.fileApexNavSelectedIconColor
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedIconColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import com.fileapex.ui.theme.fileApexNavigationRailItemColors

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme

/**
 * Medium / Expanded home: teal navigation rail + list-detail (devices | explorer).
 */
@Composable
fun AdaptiveWideHome(
    selectedTab: HomeTab,
    onSelectTab: (HomeTab) -> Unit,
    selectedTarget: BrowseTarget?,
    selectedDeviceId: String?,
    onSelectDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onExitApp: () -> Unit,
    onClearDetail: () -> Unit,
    appVersionName: String,
    devicesViewModel: DevicesViewModel,
    devicesViewMode: ExplorerViewMode = ExplorerViewMode.List,
    onToggleDevicesViewMode: () -> Unit = {},
    explorerViewMode: ExplorerViewMode = ExplorerViewMode.List,
    onToggleExplorerViewMode: () -> Unit = {},
    backgroundPersistence: BackgroundPersistenceUiState = BackgroundPersistenceUiState(),
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenBackgroundPersistenceSettings: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenAppBatteryUsageSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {},
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit = { it() },
    onOpenTransferQueue: () -> Unit = {},
    onOpenNotes: (() -> Unit)? = null
) {
    val state by devicesViewModel.uiState.collectAsState()
    val deviceRows by devicesViewModel.deviceRows.collectAsState()
    val editMode = state.deviceOrderEditMode

    val deviceOrderHeaderActions: @Composable RowScope.() -> Unit = {
        if (editMode) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                TextButton(
                    onClick = devicesViewModel::revertDeviceOrderInEditMode,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringRes("revert"), color = fileApexChromeContentColor(), fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.width(4.dp))
                TextButton(
                    onClick = devicesViewModel::saveDeviceOrderAndExitEditMode,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(stringRes("done"), color = Color(0xFF00E676), fontWeight = FontWeight.Bold)
                }
            }
        } else if (deviceRows.isNotEmpty()) {
            IconButton(onClick = devicesViewModel::enterDeviceOrderEditMode) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringRes("reorder_devices"),
                    tint = fileApexChromeContentColor()
                )
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        WideTopBar(
            onExitClick = onExitApp,
            selectedTab = selectedTab,
            hasActiveDetail = selectedTarget != null,
            devicesViewMode = devicesViewMode,
            onToggleDevicesViewMode = onToggleDevicesViewMode,
            explorerViewMode = explorerViewMode,
            onToggleExplorerViewMode = onToggleExplorerViewMode,
            onOpenTransferQueue = onOpenTransferQueue,
            onOpenNotes = onOpenNotes,
            deviceOrderHeaderActions = deviceOrderHeaderActions
        )
        val isFreestyle = LocalAppTheme.current == AppTheme.FREESTYLE
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (!isFreestyle) {
                FileApexNavigationRail(
                    selected = selectedTab,
                    onMainHomeScreen = isMainHomeScreen(
                        selectedTab = selectedTab,
                        hasActiveDetail = selectedTarget != null
                    ),
                    onDevices = {
                        onSelectTab(HomeTab.Devices)
                        if (selectedTarget != null) {
                            onClearDetail()
                        }
                    },
                    onFiles = {
                        onSelectTab(HomeTab.Files)
                        onOpenLocalFiles()
                    },
                    onSettings = { onSelectTab(HomeTab.Settings) }
                )
            }
            val isSpatialTheme = LocalAppTheme.current == AppTheme.KINETIC_SPHERE || LocalAppTheme.current == AppTheme.FREESTYLE
            when (selectedTab) {
                HomeTab.Settings -> {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        SettingsScreen(
                            appVersionName = appVersionName,
                            onBack = { onSelectTab(HomeTab.Devices) },
                            // Rail is top-level nav; on Freestyle without rail, show back button.
                            showRootBackNavigation = isFreestyle,
                            layoutMode = SettingsScreenLayoutMode.ListPane,
                            backgroundPersistence = backgroundPersistence,
                            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                            onOpenBackgroundPersistenceSettings = onOpenBackgroundPersistenceSettings,
                            onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                            onOpenAppBatteryUsageSettings = onOpenAppBatteryUsageSettings,
                            exactAlarmWarningActive = exactAlarmWarningActive,
                            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                            onOpenAppDetailsSettings = onOpenAppDetailsSettings,
                            onBeforeAllowOverCellularEnabled = onBeforeAllowOverCellularEnabled
                        )
                    }
                }
                HomeTab.Devices -> {
                    if (isSpatialTheme && selectedTarget == null) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            DevicesScreen(
                                onOpenDevice = onSelectDevice,
                                onOpenLocalFiles = onOpenLocalFiles,
                                onGenerateQr = onGenerateQr,
                                onJoinDevice = onJoinDevice,
                                onOpenSettings = { onSelectTab(HomeTab.Settings) },
                                onExitApp = onExitApp,
                                onOpenNotes = { onOpenNotes?.invoke() },
                                onOpenTransferQueue = onOpenTransferQueue,
                                viewModel = devicesViewModel,
                                layoutMode = DevicesScreenLayoutMode.FullScreen,
                                selectedDeviceId = selectedDeviceId
                            )
                        }
                    } else if (selectedTarget != null && isSpatialTheme) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            FileExplorerScreen(
                                target = selectedTarget,
                                onBack = onClearDetail
                            )
                        }
                    } else {
                        Row(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            Surface(
                                modifier = Modifier
                                    .weight(0.35f)
                                    .fillMaxHeight(),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                DevicesScreen(
                                    onOpenDevice = onSelectDevice,
                                    onOpenLocalFiles = onOpenLocalFiles,
                                    onGenerateQr = onGenerateQr,
                                    onJoinDevice = onJoinDevice,
                                    onOpenSettings = { onSelectTab(HomeTab.Settings) },
                                    onExitApp = onExitApp,
                                    onOpenNotes = { onOpenNotes?.invoke() },
                                    onOpenTransferQueue = onOpenTransferQueue,
                                    viewModel = devicesViewModel,
                                    layoutMode = DevicesScreenLayoutMode.ListPane,
                                    selectedDeviceId = selectedDeviceId
                                )
                            }
                            VerticalDivider(
                                thickness = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Box(
                                modifier = Modifier
                                    .weight(0.65f)
                                    .fillMaxHeight()
                            ) {
                                val detailTarget = selectedTarget
                                if (detailTarget == null) {
                                    DetailEmptyState()
                                } else {
                                    FileExplorerScreen(
                                        target = detailTarget,
                                        onBack = onClearDetail
                                    )
                                }
                            }
                        }
                    }
                }
                HomeTab.Files -> {
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        val localTarget = (selectedTarget as? BrowseTarget.Local)
                            ?: devicesViewModel.thisDeviceTarget()
                        FileExplorerScreen(
                            target = localTarget,
                            onBack = {
                                onClearDetail()
                                onSelectTab(HomeTab.Devices)
                            },
                            titleOverride = com.fileapex.i18n.AppI18n.t("local_files")
                        )
                    }
                }
            }
        }
        LiveTransferBanner(onOpenTransferQueue = onOpenTransferQueue)
    }
}

@Composable
private fun WideTopBar(
    onExitClick: () -> Unit,
    selectedTab: HomeTab,
    hasActiveDetail: Boolean,
    devicesViewMode: ExplorerViewMode,
    onToggleDevicesViewMode: () -> Unit,
    explorerViewMode: ExplorerViewMode,
    onToggleExplorerViewMode: () -> Unit,
    onOpenTransferQueue: () -> Unit = {},
    onOpenNotes: (() -> Unit)? = null,
    deviceOrderHeaderActions: @Composable RowScope.() -> Unit = {}
) {
    val isKineticSphere = LocalAppTheme.current == AppTheme.KINETIC_SPHERE
    val showDevicesViewToggle = selectedTab == HomeTab.Devices && !hasActiveDetail && !isKineticSphere
    val showExplorerViewToggle = selectedTab == HomeTab.Files || hasActiveDetail
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fileApexChromeBottomEdge()
            .background(fileApexChromeContainerColor())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = "FileApex",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = fileApexChromeContentColor()
            )
            if (selectedTab == HomeTab.Devices && !hasActiveDetail) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringRes("paired_devices_title"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = fileApexChromeContentColor().copy(alpha = 0.85f),
                    softWrap = true,
                    maxLines = 2
                )
            }
        }
        val liveStats by TransferActivityGuard.statsFlow.collectAsState()
        if (liveStats.isActive) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(FileApexTeal.copy(alpha = 0.20f))
                    .clickable(onClick = onOpenTransferQueue)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    progress = { liveStats.progress },
                    modifier = Modifier.size(12.dp),
                    color = FileApexTeal,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(6.dp))
                val rateText = buildList {
                    if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                    if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                }.joinToString(" • ")
                Text(
                    text = if (rateText.isNotBlank()) rateText else stringRes("sending"),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = fileApexChromeContentColor()
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }
        QueuedFilesButton(
            onClick = onOpenTransferQueue,
            iconTint = fileApexChromeContentColor()
        )
        if (onOpenNotes != null) {
            NoteHeaderButton(onOpenNotes = onOpenNotes, viewMode = devicesViewMode, modifier = Modifier.size(40.dp))
        }
        if (showDevicesViewToggle) {
            val isFreestyle = LocalAppTheme.current == AppTheme.FREESTYLE
            if (isFreestyle) {
                val freestyleMode by FileApexServices.settings.freestyleLayoutMode.collectAsState()
                val icon = when (freestyleMode) {
                    FreestyleLayoutMode.CARDS_VERTICAL -> Icons.Filled.TableRows
                    FreestyleLayoutMode.CARDS_HORIZONTAL -> Icons.Filled.ViewColumn
                    FreestyleLayoutMode.TILES -> FileApexIcons.Atr
                }
                val desc = when (freestyleMode) {
                    FreestyleLayoutMode.CARDS_VERTICAL -> "Vertical Cards Layout"
                    FreestyleLayoutMode.CARDS_HORIZONTAL -> "Horizontal Cards Layout"
                    FreestyleLayoutMode.TILES -> "Tiles Layout"
                }
                IconButton(
                    onClick = {
                        val current = FileApexServices.settings.freestyleLayoutMode.value
                        FileApexServices.settings.setFreestyleLayoutMode(current.next())
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = desc,
                        tint = fileApexChromeContentColor(),
                        modifier = Modifier.size(22.dp)
                    )
                }
            } else {
                ExplorerViewModeToggle(
                    viewMode = devicesViewMode,
                    onToggle = onToggleDevicesViewMode,
                    iconTint = fileApexChromeContentColor(),
                    modifier = Modifier.size(40.dp)
                )
            }
            deviceOrderHeaderActions()
        } else if (showExplorerViewToggle) {
            ExplorerViewModeToggle(
                viewMode = explorerViewMode,
                onToggle = onToggleExplorerViewMode,
                iconTint = fileApexChromeContentColor(),
                modifier = Modifier.size(40.dp)
            )
        }
        IconButton(
            onClick = onExitClick,
            modifier = Modifier.size(40.dp)
        ) {
            Surface(
                modifier = Modifier
                    .size(28.dp),
                shape = CircleShape,
                color = Color(0x3300E676),
                border = BorderStroke(1.dp, Color(0xFF00E676).copy(alpha = 0.70f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = stringRes("exit_fileapex"),
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FileApexNavigationRail(
    selected: HomeTab,
    onMainHomeScreen: Boolean = true,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    val devicesLabel = devicesNavLabel(onMainHomeScreen)
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
        val isPortrait = maxHeight > maxWidth
        NavigationRail(
            modifier = Modifier.fillMaxHeight(),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexChromeContentColor()
        ) {
            if (isPortrait) {
                Spacer(modifier = Modifier.weight(1f))
            } else {
                Spacer(modifier = Modifier.height(8.dp))
            }
            RailItem(
                selected = selected == HomeTab.Devices,
                onClick = onDevices,
                icon = Icons.Filled.Devices,
                label = devicesLabel
            )
            RailItem(
                selected = selected == HomeTab.Files,
                onClick = onFiles,
                icon = Icons.Filled.Folder,
                label = com.fileapex.i18n.AppI18n.t("local_files")
            )
            RailItem(
                selected = selected == HomeTab.Settings,
                onClick = onSettings,
                icon = Icons.Filled.Settings,
                label = com.fileapex.i18n.AppI18n.t("settings")
            )
            if (isPortrait) {
                Spacer(modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RailItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String
) {
    NavigationRailItem(
        selected = selected,
        onClick = onClick,
        icon = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) fileApexNavSelectedBackgroundColor() else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) {
                        fileApexNavSelectedIconColor()
                    } else {
                        fileApexNavUnselectedIconColor()
                    }
                )
            }
        },
        label = {
            Text(
                label,
                color = if (selected) {
                    fileApexNavSelectedTextColor()
                } else {
                    fileApexNavUnselectedTextColor()
                },
                softWrap = true,
                maxLines = 2
            )
        },
        colors = fileApexNavigationRailItemColors()
    )
}

@Composable
private fun DetailEmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = FileApexTeal.copy(alpha = 0.55f),
            modifier = Modifier.size(56.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringRes("select_device_files"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringRes("select_device_files_hint"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
