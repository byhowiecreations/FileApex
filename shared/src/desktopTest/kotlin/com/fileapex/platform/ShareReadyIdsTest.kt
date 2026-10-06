package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareReadyIdsTest {

    @Test
    fun onlyDevicesAlreadyMarkedReadyArePublished() {
        val ids = shareReadyDeviceIds(
            listOf(
                DesktopTrayDeviceSnapshot(id = "ready-x9d", name = "HONOR X9d", isOnline = true),
                DesktopTrayDeviceSnapshot(id = "asleep", name = "Moto Signature", isOnline = false),
                DesktopTrayDeviceSnapshot(id = "ready-fold", name = "Samsung Fold8", isOnline = true)
            )
        )
        assertEquals(listOf("ready-x9d", "ready-fold"), ids)
    }
}
