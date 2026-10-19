package com.fileapex.iroh

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohSupportTest {
    private fun device(version: String, code: Int = 0) =
        PairedDeviceEntity(
            deviceId = "d", deviceName = "d", lastKnownIp = "10.0.0.1", port = 8080, publicKeyHash = "",
            rootPath = "/", clientVersion = version, clientVersionCode = code
        )

    @Test
    fun olderBuildsAndDockerAreLeftOut() {
        assertFalse(IrohRuntime.supportsIroh(device("0.18.1")))
        assertFalse(IrohRuntime.supportsIroh(device("v0.17.1")))
        assertFalse(IrohRuntime.supportsIroh(device("0.18.2", code = 205)))
        assertFalse(IrohRuntime.supportsIroh(device("docker")))
        // Builds that put the year in front of the build number: 0.18.1 is 2026204, 0.17.1 is 2026201.
        assertFalse(IrohRuntime.supportsIroh(device("0.18.1", code = 2026204)))
        assertFalse(IrohRuntime.supportsIroh(device("0.17.1", code = 2026201)))
        assertFalse(IrohRuntime.supportsIroh(device("", code = 2026204)))
    }

    @Test
    fun newBuildsAndUnknownVersionsAreIncluded() {
        assertTrue(IrohRuntime.supportsIroh(device("0.19.0", code = 206)))
        assertTrue(IrohRuntime.supportsIroh(device("0.19.0", code = 2026206)))
        assertTrue(IrohRuntime.supportsIroh(device("", code = 2026206)))
        assertTrue(IrohRuntime.supportsIroh(device("0.19.0")))
        assertTrue(IrohRuntime.supportsIroh(device("1.0.0")))
        assertTrue(IrohRuntime.supportsIroh(device("")))
    }
}
