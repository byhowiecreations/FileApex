package com.fileapex.ui.adaptive

import com.fileapex.ui.carousel.carouselPanel
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.presentation.ExplorerSplitSession
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.theme.fileApexTileTint
import com.fileapex.ui.theme.isFileApexTiledChrome
import com.fileapex.i18n.stringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.ui.DevicesScreen
import com.fileapex.ui.DevicesScreenLayoutMode
import com.fileapex.ui.ExplorerHeaderCommands
import com.fileapex.ui.FileExplorerScreen
import com.fileapex.ui.HomeTab
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.fileApexChromeContentColor
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.platform.horizontalResizePointerIcon
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput

@Composable
internal fun RowScope.DeviceOrderHeaderActions(
    devicesViewModel: DevicesViewModel,
    editMode: Boolean,
    deviceRows: List<DeviceListRow>
) {
    val currentTheme = LocalAppTheme.current
    val isGlass = currentTheme.traits.reorderAccent
    val editTint = if (isGlass) FluxGlassPalette.accent else fileApexChromeContentColor()
    if (editMode) {
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            TextButton(
                onClick = devicesViewModel::revertDeviceOrderInEditMode,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(stringRes("revert"), color = editTint, fontWeight = FontWeight.SemiBold)
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
        if (isFileApexTiledChrome()) {
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = devicesViewModel::enterDeviceOrderEditMode),
                contentAlignment = Alignment.Center
            ) {
                JadedRaisedTile(tileSize = 28.dp) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringRes("reorder_devices"),
                        tint = fileApexTileTint(),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        } else {
            IconButton(onClick = devicesViewModel::enterDeviceOrderEditMode) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringRes("reorder_devices"),
                    tint = editTint
                )
            }
        }
    }
}

@Composable
internal fun RowScope.DevicesSplitPane(
    onSelectDevice: (BrowseTarget) -> Unit,
    onOpenLocalFiles: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onExitApp: () -> Unit,
    onOpenNotes: (() -> Unit)?,
    onOpenTransferQueue: () -> Unit,
    devicesViewModel: DevicesViewModel,
    selectedDeviceId: String?,
    selectedTarget: BrowseTarget?,
    onClearDetail: () -> Unit,
    onRegisterRefresh: (Boolean, () -> Unit) -> Unit,
    onRegisterHeaderCommands: (ExplorerHeaderCommands?) -> Unit
) {
        val splitFraction by FileApexServices.settings.desktopSplitFraction.collectAsState()
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxHeight()) {
            val totalWidthPx = constraints.maxWidth.toFloat()
            var isDraggingDivider by remember { mutableStateOf(false) }
            val dividerInteraction = remember { MutableInteractionSource() }
            val isHovered by dividerInteraction.collectIsHoveredAsState()

            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier
                        .weight(splitFraction)
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
                Box(
                    modifier = Modifier
                        .width(10.dp)
                        .fillMaxHeight()
                        .hoverable(dividerInteraction)
                        .pointerHoverIcon(horizontalResizePointerIcon())
                        .pointerInput(totalWidthPx) {
                            detectHorizontalDragGestures(
                                onDragStart = { isDraggingDivider = true },
                                onDragEnd = {
                                    isDraggingDivider = false
                                    FileApexServices.settings.setDesktopSplitFraction(FileApexServices.settings.desktopSplitFraction.value)
                                },
                                onDragCancel = {
                                    isDraggingDivider = false
                                    FileApexServices.settings.setDesktopSplitFraction(FileApexServices.settings.desktopSplitFraction.value)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (totalWidthPx > 0f) {
                                    val current = FileApexServices.settings.desktopSplitFraction.value
                                    val deltaFraction = dragAmount / totalWidthPx
                                    FileApexServices.settings.setDesktopSplitFraction(current + deltaFraction, persist = false)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    VerticalDivider(
                        thickness = if (isHovered || isDraggingDivider) 2.dp else 1.dp,
                        color = if (isHovered || isDraggingDivider) FileApexTeal else MaterialTheme.colorScheme.outlineVariant
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f - splitFraction)
                        .fillMaxHeight()
                ) {
                    val detailTarget = selectedTarget
                    if (detailTarget == null) {
                        DetailEmptyState()
                    } else {
                        FileExplorerScreen(
                            target = detailTarget,
                            onBack = onClearDetail,
                            layoutExpanded = true,
                            onRegisterRefresh = onRegisterRefresh,
                            onRegisterHeaderCommands = onRegisterHeaderCommands
                        )
                    }
                }
            }
        }
}

@Composable
internal fun RowScope.WideFilesPane(
    selectedTarget: BrowseTarget?,
    devicesViewModel: DevicesViewModel,
    onClearDetail: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onRegisterRefresh: (Boolean, () -> Unit) -> Unit,
    onRegisterHeaderCommands: (ExplorerHeaderCommands?) -> Unit,
    secondaryTarget: BrowseTarget?,
    readyDevices: List<DeviceListRow>,
    splitSession: ExplorerSplitSession
) {
        val jadedFiles = LocalAppTheme.current.traits.orbitalHome &&
            LocalKineticStyle.current == KineticStyle.JADED_STEEL
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .carouselPanel(),
            color = if (jadedFiles || LocalAppTheme.current.traits.dockHome) Color.Transparent else MaterialTheme.colorScheme.surface
        ) {
            val localTarget = (selectedTarget as? BrowseTarget.Local)
                ?: devicesViewModel.thisDeviceTarget()
            FileExplorerScreen(
                target = localTarget,
                layoutExpanded = true,
                onBack = {
                    onClearDetail()
                    onSelectTab(HomeTab.Devices)
                },
                titleOverride = com.fileapex.i18n.AppI18n.t("local_files"),
                onRegisterRefresh = onRegisterRefresh,
                onRegisterHeaderCommands = onRegisterHeaderCommands,
                secondaryTarget = secondaryTarget,
                readyDevices = readyDevices,
                onSelectSecondaryLocal = splitSession::selectLocal,
                onSelectSecondaryDevice = { deviceId ->
                    devicesViewModel.openDeviceOrExplain(deviceId) { opened ->
                        splitSession.select(opened)
                    }
                }
            )
        }
}
