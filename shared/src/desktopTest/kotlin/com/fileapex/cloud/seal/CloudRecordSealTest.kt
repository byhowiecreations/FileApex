package com.fileapex.cloud.seal

import com.fileapex.cloud.CloudDevicePresence
import com.fileapex.cloud.CloudDeviceRecord
import com.fileapex.cloud.CloudDeviceRecordParsing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudRecordSealTest {
    private var stored = ""

    private fun record(code: Int = CloudRecordSeal.MIN_VERSION_CODE) = CloudDeviceRecord(
        deviceId = "dev-1", deviceName = "Kitchen Tablet", lastKnownIp = "192.168.1.40", port = 8080, publicKeyHash = "h",
        rootPath = "/", platform = "android", clientVersion = "0.19.8b", clientVersionCode = code, updatedAtEpochMs = 5L
    )

    private fun presence() = CloudDevicePresence(
        deviceId = "dev-1", lastKnownIp = "192.168.1.40", port = 8080, publicKeyHash = "h", rootPath = "/",
        platform = "android", clientVersion = "0.19.8b", clientVersionCode = CloudRecordSeal.MIN_VERSION_CODE, updatedAtEpochMs = 5L
    )

    private fun asFirestore(r: CloudDeviceRecord): Map<String, Any?> = mapOf(
        "deviceId" to r.deviceId, "deviceName" to r.deviceName, "lastKnownIp" to r.lastKnownIp, "port" to r.port,
        "clientVersionCode" to r.clientVersionCode
    ) + r.sealed

    @Before
    fun setUp() {
        stored = ""
        ClusterRecordKeys.read = { stored }
        ClusterRecordKeys.write = { stored = it }
        ClusterRecordKeys.forgetForTests()
        ClusterRecordKeys.onChanged = {}
        CloudRecordSeal.forgetVersionsForTests()
    }

    @After
    fun tearDown() = ClusterRecordKeys.forgetForTests()

    @Test
    fun anOlderPeerInTheClusterKeepsThePlainFieldsWritten() {
        CloudRecordSeal.noteVersion("old", CloudRecordSeal.MIN_VERSION_CODE - 1)
        val out = CloudRecordSeal.outgoing(record())
        assertEquals("Kitchen Tablet", out.deviceName)
        assertEquals("192.168.1.40", out.lastKnownIp)
        assertTrue(out.sealed.keys.containsAll(listOf(CloudRecordSeal.FIELD_NAME, CloudRecordSeal.FIELD_NET)))
    }

    @Test
    fun aDeviceDeletedFromTheRegistryNoLongerKeepsThePlainFieldsWritten() {
        CloudRecordSeal.noteVersion("old", CloudRecordSeal.MIN_VERSION_CODE - 1)
        assertTrue(CloudRecordSeal.plainStillNeeded())
        CloudRecordSeal.replaceVersions(listOf(record()))
        assertFalse(CloudRecordSeal.plainStillNeeded())
        assertEquals("", CloudRecordSeal.outgoing(record()).deviceName)
    }

    @Test
    fun aSnapshotStillHoldingAnOldBuildKeepsThePlainFieldsWritten() {
        CloudRecordSeal.replaceVersions(listOf(record(), record(CloudRecordSeal.MIN_VERSION_CODE - 1).copy(deviceId = "old")))
        assertTrue(CloudRecordSeal.plainStillNeeded())
    }

    @Test
    fun anEmptySnapshotKeepsThePlainFieldsWritten() {
        CloudRecordSeal.replaceVersions(emptyList())
        assertTrue(CloudRecordSeal.plainStillNeeded())
    }

    @Test
    fun whenEveryDeviceCanReadSealedFieldsThePlainOnesGoEmpty() {
        CloudRecordSeal.noteVersion("dev-1", CloudRecordSeal.MIN_VERSION_CODE)
        val out = CloudRecordSeal.outgoing(record())
        assertEquals("", out.deviceName)
        assertEquals("", out.lastKnownIp)
        assertEquals(0, out.port)
        val presence = CloudRecordSeal.outgoing(presence())
        assertEquals("", presence.lastKnownIp)
        assertFalse(out.sealed.values.any { "Kitchen" in it || "192.168" in it })
    }

    @Test
    fun aDeviceWithTheKeyReadsNameAddressAndPortBack() {
        CloudRecordSeal.noteVersion("dev-1", CloudRecordSeal.MIN_VERSION_CODE)
        val read = CloudDeviceRecordParsing.fromFirestoreMap(asFirestore(CloudRecordSeal.outgoing(record())), "dev-1")
        assertEquals("Kitchen Tablet", read.deviceName)
        assertEquals("192.168.1.40", read.lastKnownIp)
        assertEquals(8080, read.port)
    }

    @Test
    fun aDeviceWithoutTheKeySeesNothingPersonal() {
        CloudRecordSeal.noteVersion("dev-1", CloudRecordSeal.MIN_VERSION_CODE)
        val data = asFirestore(CloudRecordSeal.outgoing(record()))
        stored = ""
        ClusterRecordKeys.forgetForTests()
        val read = CloudDeviceRecordParsing.fromFirestoreMap(data, "dev-1")
        assertEquals("", read.deviceName)
        assertEquals("", read.lastKnownIp)
    }

    @Test
    fun aSealedValueDoesNotOpenOnAnotherDevicesRecord() {
        CloudRecordSeal.noteVersion("dev-1", CloudRecordSeal.MIN_VERSION_CODE)
        val data = asFirestore(CloudRecordSeal.outgoing(record())).toMutableMap()
        data["deviceId"] = "dev-2"
        val read = CloudDeviceRecordParsing.fromFirestoreMap(data, "dev-2")
        assertEquals("", read.deviceName)
    }

    @Test
    fun theRenamePatchSealsTheNewName() {
        CloudRecordSeal.noteVersion("dev-1", CloudRecordSeal.MIN_VERSION_CODE)
        val (plain, sealed) = CloudRecordSeal.forRename("dev-1", "Den Laptop")
        assertEquals("", plain)
        assertFalse(sealed.getValue(CloudRecordSeal.FIELD_NAME).contains("Den"))
    }

    @Test
    fun theHigherEpochWinsAndEqualEpochsSettleOnTheLowerId() {
        val low = ClusterRecordKey.generate(1)
        val high = ClusterRecordKey.generate(2)
        assertTrue(high.beats(low))
        assertFalse(low.beats(high))
        val a = ClusterRecordKey(1, "aaaa", low.toJson().let { ClusterRecordKey.fromJson(it)!!.let { k -> java.util.Base64.getEncoder().encodeToString(k.bytes()) } })
        val b = ClusterRecordKey(1, "bbbb", java.util.Base64.getEncoder().encodeToString(low.bytes()))
        assertTrue(a.beats(b))
        assertFalse(b.beats(a))
        assertTrue(low.beats(null))
    }

    @Test
    fun adoptingAPeersKeyReplacesOnlyAWeakerOne() {
        val own = ClusterRecordKeys.ensure()!!
        var changes = 0
        ClusterRecordKeys.onChanged = { changes++ }
        assertFalse(ClusterRecordKeys.adopt(own))
        val better = ClusterRecordKey.generate(own.epoch + 1)
        assertTrue(ClusterRecordKeys.adopt(better))
        assertEquals(better.id, ClusterRecordKeys.current()!!.id)
        assertEquals(1, changes)
        assertFalse(ClusterRecordKeys.adopt(own))
    }

    @Test
    fun rotatingRaisesTheEpochAndOnlyWhenAKeyExists() {
        ClusterRecordKeys.rotate()
        assertNull(ClusterRecordKeys.current())
        val first = ClusterRecordKeys.ensure()!!
        ClusterRecordKeys.rotate()
        assertEquals(first.epoch + 1, ClusterRecordKeys.current()!!.epoch)
        assertNotEquals(first.id, ClusterRecordKeys.current()!!.id)
    }

    @Test
    fun aMalformedKeyIsNotAdopted() {
        assertNull(ClusterRecordKey.fromJson("""{"epoch":1,"id":"x","keyBase64":"AAAA"}"""))
        assertNull(ClusterRecordKey.fromJson("not json"))
    }
}
