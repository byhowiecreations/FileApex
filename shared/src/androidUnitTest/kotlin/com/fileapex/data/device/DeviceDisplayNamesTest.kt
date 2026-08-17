package com.fileapex.data.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDisplayNamesTest {

    @Test
    fun factoryIsManufacturerPlusModel() {
        assertTrue(DeviceDisplayNames.isFactory("HONOR MBH-N49", "HONOR", "MBH-N49"))
        assertTrue(DeviceDisplayNames.isFactory("MBH-N49", "HONOR", "MBH-N49"))
        assertFalse(DeviceDisplayNames.isFactory("Honor Magic v5", "HONOR", "MBH-N49"))
    }

    @Test
    fun heartbeatFactoryDoesNotReplaceCustomName() {
        val kept = DeviceDisplayNames.merge(
            existingName = "Honor Magic v5",
            incomingName = "HONOR MBH-N49",
            make = "HONOR",
            model = "MBH-N49"
        )
        assertEquals("Honor Magic v5", kept)
    }

    @Test
    fun deviceSideCustomRenameStillReplacesFactory() {
        val updated = DeviceDisplayNames.merge(
            existingName = "HONOR MBH-N49",
            incomingName = "Kitchen Honor",
            make = "HONOR",
            model = "MBH-N49"
        )
        assertEquals("Kitchen Honor", updated)
    }

    @Test
    fun newerCustomNameFromTheDeviceWins() {
        val updated = DeviceDisplayNames.merge(
            existingName = "Honor Magic v5",
            incomingName = "Kitchen Honor",
            make = "HONOR",
            model = "MBH-N49"
        )
        assertEquals("Kitchen Honor", updated)
    }
}
