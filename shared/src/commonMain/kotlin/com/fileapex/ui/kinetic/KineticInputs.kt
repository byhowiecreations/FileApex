package com.fileapex.ui.kinetic

import com.fileapex.presentation.DeviceListRow

internal class KineticInputs(
    val deviceRows: List<DeviceListRow>,
    val onOpenDevice: (String) -> Unit,
    val onRenameDevice: (deviceId: String, deviceName: String) -> Unit,
    val onDeviceDetails: (deviceId: String) -> Unit,
    val onSendClipboardDevice: (deviceId: String) -> Unit,
    val onRemoveDevice: (deviceId: String, deviceName: String) -> Unit,
    val onFilesDropped: (deviceId: String, paths: List<String>) -> Unit,
    val onGenerateQr: () -> Unit = {},
    val onJoinDevice: () -> Unit = {},
    val onCheckBatteries: (() -> Unit)? = null,
    val onSendClipboard: (() -> Unit)? = null,
    val onOpenNotes: () -> Unit = {}
)
