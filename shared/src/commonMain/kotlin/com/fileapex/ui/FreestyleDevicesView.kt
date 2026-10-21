package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.devices.FreestyleDeviceNodes
import com.fileapex.ui.devices.FreestyleEffects
import com.fileapex.ui.devices.FreestyleFilesWindow
import com.fileapex.ui.devices.FreestyleInputs
import com.fileapex.ui.devices.FreestyleLive
import com.fileapex.ui.devices.FreestyleOptionGhost
import com.fileapex.ui.devices.FreestyleOptionsButton
import com.fileapex.ui.devices.FreestylePinnedActions
import com.fileapex.ui.devices.FreestyleTutorialDialog
import com.fileapex.ui.devices.freestyleGeometry
import com.fileapex.ui.devices.rememberFreestylePersisted
import com.fileapex.ui.devices.rememberFreestyleScope

@Composable
fun FreestyleDevicesView(
    deviceRows: List<DeviceListRow>,
    connectingDeviceId: String?,
    selectedDeviceId: String?,
    isEditMode: Boolean,
    onOpenDevice: (String) -> Unit,
    onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    onDeviceDetails: (deviceId: String) -> Unit,
    onSendClipboardDevice: (deviceId: String) -> Unit,
    onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    onSaveDeviceCardPosition: (deviceId: String, x: Float, y: Float) -> Unit,
    onSaveDeviceTilePosition: (deviceId: String, x: Float, y: Float) -> Unit,
    onSaveDeviceCardMenuOrder: (deviceId: String, order: String) -> Unit,
    onSaveDeviceTileMenuOrder: (deviceId: String, order: String) -> Unit,
    onGenerateQr: () -> Unit = {},
    onJoinDevice: () -> Unit = {},
    onCheckBatteries: (() -> Unit)? = null,
    onSendClipboard: (() -> Unit)? = null,
    onOpenLocalFiles: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    thisDeviceTarget: BrowseTarget? = null,
    onResolveBrowseTarget: ((deviceId: String, onTargetResolved: (BrowseTarget) -> Unit) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val inputs = FreestyleInputs(
        deviceRows = deviceRows,
        connectingDeviceId = connectingDeviceId,
        selectedDeviceId = selectedDeviceId,
        isEditMode = isEditMode,
        onOpenDevice = onOpenDevice,
        onRenameDevice = onRenameDevice,
        onDeviceDetails = onDeviceDetails,
        onSendClipboardDevice = onSendClipboardDevice,
        onRemoveDevice = onRemoveDevice,
        onFilesDropped = onFilesDropped,
        onSaveDeviceCardPosition = onSaveDeviceCardPosition,
        onSaveDeviceTilePosition = onSaveDeviceTilePosition,
        onSaveDeviceCardMenuOrder = onSaveDeviceCardMenuOrder,
        onSaveDeviceTileMenuOrder = onSaveDeviceTileMenuOrder,
        onGenerateQr = onGenerateQr,
        onJoinDevice = onJoinDevice,
        onCheckBatteries = onCheckBatteries,
        onSendClipboard = onSendClipboard,
        onOpenLocalFiles = onOpenLocalFiles,
        onOpenSettings = onOpenSettings,
        thisDeviceTarget = thisDeviceTarget,
        onResolveBrowseTarget = onResolveBrowseTarget
    )
    val persisted = rememberFreestylePersisted()
    val live = remember { FreestyleLive() }
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!isEditMode) {
                    live.activeDeviceId = null
                    live.optionsMenuExpanded = false
                }
            }
    ) {
        val geometry = freestyleGeometry(density, maxWidth, maxHeight)
        val scope = rememberFreestyleScope(inputs, persisted, geometry, live)
        scope.FreestyleEffects()
        scope.FreestyleOptionsButton()
        scope.FreestyleOptionGhost()
        scope.FreestylePinnedActions()
        scope.FreestyleDeviceNodes()
        scope.FreestyleFilesWindow()
        scope.FreestyleTutorialDialog()
    }
}
