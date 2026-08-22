package com.fileapex.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceIconResolverTest {

    @Test
    fun macAndWindowsUseOsBrandedDesktops() {
        assertEquals(
            DeviceIconKind.MacDesktop,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "MacBook Pro",
                    hardware = DeviceHardwareProfile(os = "macos", platform = "desktop", deviceMake = "Apple")
                )
            )
        )
        assertEquals(
            DeviceIconKind.WindowsPc,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "DESKTOP-HOME",
                    hardware = DeviceHardwareProfile(os = "windows", platform = "desktop")
                )
            )
        )
    }

    @Test
    fun phonesResolveToFormFactor() {
        assertEquals(
            DeviceIconKind.FoldablePhone,
            resolveDeviceIconKind(DeviceIconProfile(deviceName = "Pixel Fold"))
        )
        assertEquals(
            DeviceIconKind.PixelPhone,
            resolveDeviceIconKind(DeviceIconProfile(deviceName = "Google Pixel 10 Pro XL"))
        )
        assertEquals(
            DeviceIconKind.SamsungPhone,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "Galaxy S25",
                    hardware = DeviceHardwareProfile(deviceMake = "samsung")
                )
            )
        )
    }
}
