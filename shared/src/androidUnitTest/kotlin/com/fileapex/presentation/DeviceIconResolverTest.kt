package com.fileapex.presentation

import fileapex.shared.generated.resources.*
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
    fun dockerDaemonUsesDockerMark() {
        assertEquals(
            DeviceIconKind.Docker,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "Docker",
                    hardware = DeviceHardwareProfile(
                        os = "linux",
                        platform = "linux",
                        deviceMake = "FileApex",
                        deviceModel = "OMV"
                    ),
                    clientVersion = "docker"
                )
            )
        )
        assertEquals(
            DeviceIconKind.Docker,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "Backup",
                    hardware = DeviceHardwareProfile(deviceMake = "FileApex", deviceModel = "OMV")
                )
            )
        )
        assertEquals(
            DeviceIconKind.LinuxServer,
            resolveDeviceIconKind(
                DeviceIconProfile(
                    deviceName = "OMV Backup",
                    hardware = DeviceHardwareProfile(os = "linux", platform = "linux", deviceMake = "Debian")
                )
            )
        )
        val linuxApp = DeviceIconProfile(
            deviceName = "Workshop",
            hardware = DeviceHardwareProfile(os = "linux", platform = "Linux", deviceMake = "Linux"),
            clientVersion = "0.16.1d"
        )
        assertEquals(DeviceIconKind.LinuxServer, resolveDeviceIconKind(linuxApp))
        assertEquals(Res.drawable.dev_fs_server, resolveFreestyleDrawable(linuxApp))
        assertEquals(Res.drawable.dev_flux_laptop, resolveFluxDrawable(linuxApp))
        val docker = DeviceIconProfile(
            deviceName = "Docker",
            hardware = DeviceHardwareProfile(os = "linux", platform = "linux", deviceMake = "FileApex", deviceModel = "OMV"),
            clientVersion = "docker"
        )
        assertEquals(Res.drawable.dev_fs_container, resolveFreestyleDrawable(docker))
        assertEquals(Res.drawable.dev_flux_laptop, resolveFluxDrawable(docker))
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

    @Test
    fun oppoFindX9ResolvesToOppoIconAndNotHonor() {
        val oppoProfile = DeviceIconProfile(
            deviceName = "Oppo Find X9 Pro",
            hardware = DeviceHardwareProfile(os = "android", platform = "phone", deviceMake = "OPPO", deviceModel = "PKB110")
        )
        val fsIcon = resolveFreestyleDrawable(oppoProfile)
        assertEquals(Res.drawable.dev_fs_oppo_find_x9_pro, fsIcon)
        org.junit.Assert.assertNotEquals(Res.drawable.dev_fs_honor_x9d, fsIcon)

        val honorProfile = DeviceIconProfile(
            deviceName = "My Phone",
            hardware = DeviceHardwareProfile(os = "android", platform = "phone", deviceMake = "HONOR", deviceModel = "ALI-NX1")
        )
        val honorIcon = resolveFreestyleDrawable(honorProfile)
        assertEquals(Res.drawable.dev_fs_honor_x9d, honorIcon)
    }

    @Test
    fun deviceModelLookupResolvesOemModels() {
        assertEquals("OPPO Find X8 Pro", DeviceModelLookup.lookupMarketingName("PKB110"))
        assertEquals("HONOR X9b", DeviceModelLookup.lookupMarketingName("ALI-NX1"))
        assertEquals("oppo", DeviceModelLookup.inferMake("CPH2609"))
        assertEquals("samsung", DeviceModelLookup.inferMake("SM-S928B"))
    }
}
