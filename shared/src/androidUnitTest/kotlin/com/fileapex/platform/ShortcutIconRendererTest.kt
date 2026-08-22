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
    }

    private fun assertEqualsKind(expected: DeviceIconKind, peer: PairedDeviceEntity) {
        org.junit.Assert.assertEquals(expected, ShortcutIconRenderer.iconKind(peer))
    }
}
