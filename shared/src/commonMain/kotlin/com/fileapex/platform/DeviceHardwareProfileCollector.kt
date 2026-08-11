package com.fileapex.platform

import com.fileapex.presentation.DeviceHardwareProfile

/** Local hardware identity for LAN peer broadcasts and pairing payloads. */
expect fun localDeviceHardwareProfile(): DeviceHardwareProfile

/** Static hardware fingerprint map (manufacturer, model, device, board) for cloud reconciliation. */
expect fun localHardwareFingerprint(): Map<String, String>
