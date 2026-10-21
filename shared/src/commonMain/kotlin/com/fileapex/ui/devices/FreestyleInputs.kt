package com.fileapex.ui.devices

import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.DeviceListRow

internal class FreestyleInputs(
    val deviceRows: List<DeviceListRow>,
    val connectingDeviceId: String?,
    val selectedDeviceId: String?,
    val isEditMode: Boolean,
    val onOpenDevice: (String) -> Unit,
    val onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    val onDeviceDetails: (deviceId: String) -> Unit,
    val onSendClipboardDevice: (deviceId: String) -> Unit,
    val onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    val onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    val onSaveDeviceCardPosition: (deviceId: String, x: Float, y: Float) -> Unit,
    val onSaveDeviceTilePosition: (deviceId: String, x: Float, y: Float) -> Unit,
    val onSaveDeviceCardMenuOrder: (deviceId: String, order: String) -> Unit,
    val onSaveDeviceTileMenuOrder: (deviceId: String, order: String) -> Unit,
    val onGenerateQr: () -> Unit,
    val onJoinDevice: () -> Unit,
    val onCheckBatteries: (() -> Unit)?,
    val onSendClipboard: (() -> Unit)?,
    val onOpenLocalFiles: (() -> Unit)?,
    val onOpenSettings: (() -> Unit)?,
    val thisDeviceTarget: BrowseTarget?,
    val onResolveBrowseTarget: ((deviceId: String, onTargetResolved: (BrowseTarget) -> Unit) -> Unit)?
)
