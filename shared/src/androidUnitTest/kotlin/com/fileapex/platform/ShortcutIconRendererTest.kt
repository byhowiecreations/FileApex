package com.fileapex.platform

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.presentation.DeviceIconKind
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShortcutIconRendererTest {

    @Test
    fun bulletinAndDeviceIconsRender() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(ShortcutIconRenderer.bulletinBoard(context))
        val mac = PairedDeviceEntity(
            deviceId = "mac-1",
            deviceName = "MacBook Pro",
            lastKnownIp = "192.168.1.10",
            port = 17420,
            publicKeyHash = "hash",
            rootPath = "/",
            platform = "desktop",
            os = "macos",
            deviceMake = "Apple",
            deviceModel = "MacBookPro18,3"
        )
        assertEqualsKind(DeviceIconKind.MacDesktop, mac)
        assertNotNull(ShortcutIconRenderer.device(context, mac))

        val samples = listOf(
            mac,
            mac.copy(deviceId = "win-1", deviceName = "Windows PC", os = "windows", platform = "desktop", deviceMake = "", deviceModel = ""),
            mac.copy(deviceId = "doc-1", deviceName = "Docker", os = "linux", platform = "docker", deviceMake = "", deviceModel = ""),
            mac.copy(deviceId = "lin-1", deviceName = "Linux Box", os = "linux", platform = "desktop", deviceMake = "", deviceModel = ""),
            mac.copy(deviceId = "fold-1", deviceName = "Samsung Fold8", os = "android", platform = "android", deviceMake = "Samsung", deviceModel = "SM-F966U"),
            mac.copy(deviceId = "flip-1", deviceName = "moto razr fold", os = "android", platform = "android", deviceMake = "Motorola", deviceModel = "razr fold"),
            mac.copy(deviceId = "pix-1", deviceName = "Pixel 10", os = "android", platform = "android", deviceMake = "Google", deviceModel = "Pixel 10"),
            mac.copy(deviceId = "sam-1", deviceName = "Galaxy S26", os = "android", platform = "android", deviceMake = "Samsung", deviceModel = "SM-S948U"),
            mac.copy(deviceId = "tab-1", deviceName = "Galaxy Tab", os = "android", platform = "android", deviceMake = "Samsung", deviceModel = "Galaxy Tab S10"),
            mac.copy(deviceId = "and-1", deviceName = "Android Phone", os = "android", platform = "android", deviceMake = "OnePlus", deviceModel = "CPH2583")
        )
        samples.forEach { sample ->
            assertNotNull("Expected icon for ${sample.deviceName}", ShortcutIconRenderer.device(context, sample))
        }
    }

    private fun assertEqualsKind(expected: DeviceIconKind, peer: PairedDeviceEntity) {
        org.junit.Assert.assertEquals(expected, ShortcutIconRenderer.iconKind(peer))
    }
}
