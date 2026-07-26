package com.fileapex.platform

import com.fileapex.presentation.DeviceHardwareProfile

/** Local hardware identity for LAN peer broadcasts and pairing payloads. */
expect fun localDeviceHardwareProfile(): DeviceHardwareProfile
