package com.fileapex.data.device

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.domain.peer.PeerNodeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device owns its name. A removed device that rejoins is a new member under the name it announces. */
class RejoinNameTest {
    private class Node(val id: String) {
        val dao = InMemoryDeviceDao()
        val repo = DeviceRepository(deviceDao = dao, localDeviceProvider = { LocalDeviceRef(id, emptySet()) })
        suspend fun nameOf(other: String) = repo.getDevice(other)?.deviceName
    }

    private fun entity(id: String, name: String = id) = PairedDeviceEntity(
        deviceId = id, deviceName = name, lastKnownIp = "192.168.1.${id.hashCode() and 0x7f}", port = 8080,
        publicKeyHash = "hash-$id", publicKey = "key-$id", rootPath = "/"
    )

    private fun announced(id: String, name: String) = PeerNodeState(
        deviceId = id, deviceName = name, port = 8080, publicKeyHash = "hash-$id", publicKey = "key-$id",
        deviceMake = "motorola", deviceModel = "motorola signature"
    )

    private suspend fun pairedPair(signatureName: String): Pair<Node, Node> {
        val mac = Node("mac"); val signature = Node("signature")
        mac.repo.adoptFromPairing(entity("signature", signatureName))
        signature.repo.adoptFromPairing(entity("mac"))
        return mac to signature
    }

    @Test
    fun aRejoiningDeviceIsHeldUnderTheNameItAnnouncesNotTheOldLabel() = runBlocking {
        val (mac, signature) = pairedPair("motorola motorola signature")
        mac.repo.rename("signature", "moto signature")
        assertEquals("moto signature", mac.nameOf("signature"))

        assertTrue(mac.repo.removePermanently("signature"))
        signature.repo.forgetCluster()
        mac.repo.adoptFromPairing(entity("signature", "motorola motorola signature"))

        assertEquals("motorola motorola signature", mac.nameOf("signature"))
    }

    @Test
    fun aDeviceThatAnnouncesItsOwnNewNameIsAcceptedByItsPeers() = runBlocking {
        val (mac, _) = pairedPair("motorola motorola signature")
        mac.repo.applyChainProfile(announced("signature", "moto signature"), ownProfile = true)
        assertEquals("moto signature", mac.nameOf("signature"))
    }

    @Test
    fun aRelayedCopyOfAnOldNameNeverUndoesARenameTheDeviceAnnouncedItself() = runBlocking {
        val (mac, _) = pairedPair("motorola motorola signature")
        mac.repo.applyChainProfile(announced("signature", "moto signature"), ownProfile = true)
        mac.repo.applyChainProfile(announced("signature", "motorola motorola signature"), ownProfile = false)
        assertEquals("moto signature", mac.nameOf("signature"))
    }

    @Test
    fun aDeviceThatAnnouncesItsHardwareNameIsAcceptedUnderItEvenOverAnOlderLabel() = runBlocking {
        val (mac, _) = pairedPair("motorola motorola signature")
        mac.repo.rename("signature", "moto signature")
        mac.repo.applyChainProfile(announced("signature", "motorola motorola signature"), ownProfile = true)
        assertEquals("motorola motorola signature", mac.nameOf("signature"))
    }

    @Test
    fun aRelayedHardwareNameStillNeverReplacesAHeldLabel() = runBlocking {
        val (mac, _) = pairedPair("motorola motorola signature")
        mac.repo.rename("signature", "moto signature")
        mac.repo.applyChainProfile(announced("signature", "motorola motorola signature"), ownProfile = false)
        assertEquals("moto signature", mac.nameOf("signature"))
    }
}
