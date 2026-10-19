package com.fileapex.data.bulletin

import com.fileapex.data.db.PairedDeviceEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulletinDockerSkipTest {
    private fun device(version: String, code: Int) = PairedDeviceEntity(
        deviceId = "d", deviceName = "n", lastKnownIp = "192.168.1.5", port = 8080, publicKeyHash = "",
        rootPath = "/", clientVersion = version, clientVersionCode = code
    )

    @Test
    fun aDockerNodeHasNoBulletinBoardAndIsNeverSentPosts() {
        assertFalse(BulletinBoardPolicy.takesPartInBulletin(device("docker", 206)))
        assertFalse(BulletinBoardPolicy.takesPartInBulletin(device("docker", 0)))
    }

    @Test
    fun anAppInstallTakesPart() {
        assertTrue(BulletinBoardPolicy.takesPartInBulletin(device("0.19.1", 207)))
        assertTrue(BulletinBoardPolicy.takesPartInBulletin(device("0.8.0", 120)))
    }
}
