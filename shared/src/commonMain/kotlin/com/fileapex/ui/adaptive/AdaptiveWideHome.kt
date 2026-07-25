package com.fileapex.ui.adaptive

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.DevicesScreen
import com.fileapex.ui.DevicesScreenLayoutMode
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.HomeTab
import com.fileapex.ui.SettingsScreen
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FileApexTealDark

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
    onScanQr: () -> Unit,
    onExitApp: () -> Unit,
    onClearDetail: () -> Unit,
    appVersionName: String,
    devicesViewModel: DevicesViewModel,
    batteryOptimizationRestricted: Boolean = false,
    unusedAppRestrictionsActive: Boolean = false,
    showMotorolaSmartUseGuidance: Boolean = false,
    onRequestBatteryUnrestricted: () -> Unit = {},
    onOpenUnusedAppRestrictionsSettings: () -> Unit = {},
    onOpenMotorolaBackgroundAppsSettings: () -> Unit = {},
    exactAlarmWarningActive: Boolean = false,
    onOpenExactAlarmSettings: () -> Unit = {},
    onOpenAppDetailsSettings: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        WideTopBar(onExitClick = onExitApp)
        Row(modifier = Modifier.fillMaxSize()) {
            FileApexNavigationRail(
                selected = selectedTab,
                onDevices = { onSelectTab(HomeTab.Devices) },
                onFiles = {
                    onSelectTab(HomeTab.Files)
                    onOpenLocalFiles()
                },
                onSettings = { onSelectTab(HomeTab.Settings) }
            )
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
                            // Rail is top-level nav; no redundant up/back on Settings root.
                            showRootBackNavigation = false,
                            layoutMode = SettingsScreenLayoutMode.ListPane,
                            batteryOptimizationRestricted = batteryOptimizationRestricted,
                            unusedAppRestrictionsActive = unusedAppRestrictionsActive,
                            showMotorolaSmartUseGuidance = showMotorolaSmartUseGuidance,
                            onRequestBatteryUnrestricted = onRequestBatteryUnrestricted,
                            onOpenUnusedAppRestrictionsSettings = onOpenUnusedAppRestrictionsSettings,
                            onOpenMotorolaBackgroundAppsSettings = onOpenMotorolaBackgroundAppsSettings,
                            exactAlarmWarningActive = exactAlarmWarningActive,
                            onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                            onOpenAppDetailsSettings = onOpenAppDetailsSettings
                        )
                    }
                }
                HomeTab.Devices, HomeTab.Files -> {
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
                                onScanQr = onScanQr,
                                onOpenSettings = { onSelectTab(HomeTab.Settings) },
                                onExitApp = onExitApp,
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
        }
    }
}

@Composable
private fun WideTopBar(onExitClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FileApexTeal)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "FileApex",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onExitClick) {
            Icon(
                imageVector = Icons.Filled.PowerSettingsNew,
                contentDescription = "Exit FileApex",
                tint = Color.White
            )
        }
    }
}

@Composable
fun FileApexNavigationRail(
    selected: HomeTab,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = FileApexTeal,
        contentColor = Color.White,
        header = { Spacer(modifier = Modifier.height(8.dp)) }
    ) {
        RailItem(
            selected = selected == HomeTab.Devices,
            onClick = onDevices,
            icon = Icons.Filled.Devices,
            label = "Devices"
        )
        RailItem(
            selected = selected == HomeTab.Files,
            onClick = onFiles,
            icon = Icons.Filled.Folder,
            label = "Local Files"
        )
        RailItem(
            selected = selected == HomeTab.Settings,
            onClick = onSettings,
            icon = Icons.Filled.Settings,
            label = "Settings"
        )
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
                    .background(if (selected) Color.White else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (selected) FileApexTealDark else Color.White
                )
            }
        },
        label = { Text(label, color = Color.White) },
        colors = NavigationRailItemDefaults.colors(
            indicatorColor = Color.Transparent,
            selectedIconColor = FileApexTealDark,
            unselectedIconColor = Color.White,
            selectedTextColor = Color.White,
            unselectedTextColor = Color.White.copy(alpha = 0.85f)
        )
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
            text = "Select a device to view files",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Choose a paired peer from the list, or open Local Files from the navigation bar.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
