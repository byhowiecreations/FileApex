package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalNoticeTest {
    private val honor = PairedDeviceEntity(
        deviceId = "honor", deviceName = "Honor", lastKnownIp = "192.168.1.60", port = 8080,
        publicKeyHash = "hh", publicKey = "kk", rootPath = "/", clusterVersion = 1L
    )

    private fun notice(reason: String = "user_removed", by: String = "mac") = RemovedDeviceRecord(
        deviceId = "honor", clusterVersion = 500L, removedAt = 500L,
        membershipProtocol = 0, reason = reason, removedBy = by
    )

    private fun judge(
        record: RemovedDeviceRecord = notice(),
        sender: String? = "mac",
        member: Boolean = true,
        local: String = "honor"
    ) = RemovalNotice.judge(record, local, sender, member)

    @Test
    fun aUserRemovalFromTheAuthenticatedRemoverIsActedOn() {
        assertEquals(RemovalVerdict.Act, judge())
    }

    @Test
    fun aNoticeNamingAnotherDeviceIsNotForThisOne() {
        assertEquals(RemovalVerdict.NotForThisDevice, judge(local = "signature"))
    }

    @Test
    fun aSenderTheTlsFrontDidNotAuthenticateIsOnlyAHint() {
        assertTrue(judge(sender = null) is RemovalVerdict.Hint)
        assertTrue(judge(sender = "") is RemovalVerdict.Hint)
    }

    @Test
    fun aSenderOutsideTheRosterIsOnlyAHint() {
        assertTrue(judge(member = false) is RemovalVerdict.Hint)
    }

    @Test
    fun anyReasonButUserRemovedIsOnlyAHintAndSaysSo() {
        for (reason in listOf(RemovalReason.SUPERSEDED.code, RemovalReason.LEFT_CLUSTER.code, "", "something new")) {
            val verdict = judge(record = notice(reason = reason))
            assertTrue("reason '$reason'", verdict is RemovalVerdict.Hint)
        }
        val named = (judge(record = notice(reason = "superseded")) as RemovalVerdict.Hint).why
        assertTrue(named, "superseded" in named)
        val none = (judge(record = notice(reason = "")) as RemovalVerdict.Hint).why
        assertTrue(none, "no reason" in none)
    }

    @Test
    fun aUserRemovalRelayedByAnotherRosterDeviceIsActedOnAndKeepsWhoMadeIt() {
        assertEquals(RemovalVerdict.Act, judge(record = notice(by = "mac"), sender = "signature"))
    }

    @Test
    fun aRelayedRemovalFromASenderWhoCannotCommandMembershipIsOnlyAHint() {
        val verdict = RemovalNotice.judge(
            record = notice(by = "nothing").copy(deviceId = "mac"),
            localDeviceId = "mac",
            authenticatedSenderId = "signature",
            senderIsRosterMember = true,
            senderCanCommand = false
        )
        assertTrue(verdict is RemovalVerdict.Hint)
    }

    @Test
    fun theNoticeCarriesTheReasonAndWhoRemovedTheDevice() {
        val record = RemovalNotice.build(honor, clusterVersion = 500L, reason = RemovalReason.USER_REMOVED, removerId = "mac")
        assertEquals("honor", record.deviceId)
        assertEquals("user_removed", record.reason)
        assertEquals("mac", record.removedBy)
        assertEquals(500L, record.removedAt)
        assertEquals(0, record.membershipProtocol)
        assertEquals("hh", record.publicKeyHash)
    }

    @Test
    fun anOldNoticeWithoutTheNewFieldsStillDecodesAndIsAHint() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(RemovedDeviceRecord.serializer(), """{"deviceId":"honor","clusterVersion":500,"removedAt":500,"membershipProtocol":2}""")
        assertEquals("", old.reason)
        assertTrue(judge(record = old) is RemovalVerdict.Hint)
        val newer = json.encodeToString(RemovedDeviceRecord.serializer(), notice())
        assertTrue("user_removed" in newer && "removedBy" in newer)
        val oldBuild = Json { ignoreUnknownKeys = true }.decodeFromString(RemovedDeviceRecord.serializer(), newer)
        assertEquals("honor", oldBuild.deviceId)
    }
}
