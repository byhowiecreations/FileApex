package com.fileapex.cloud.device

import com.fileapex.membership.RecordKind
import com.fileapex.membership.ChainRecord
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityServiceTest {
    private class FakeRelay : DeviceRelay {
        var key: String? = "relay-key"
        val enrolAnswers = ArrayDeque<RelayAnswer>()
        var enrols = 0
        val sentKeys = mutableListOf<String>()
        val enrolRecords = mutableListOf<List<ChainRecord>>()
        var head: RelayChainHead? = RelayChainHead("", 0L, "")
        var uploadAnswer: RelayAnswer = RelayAnswer.Ok(JsonObject(emptyMap()))
        val uploads = mutableListOf<List<ChainRecord>>()
        val startOvers = mutableListOf<Boolean>()
        override suspend fun relayKey() = key
        override suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String, clipboardPublicKey: String, records: List<ChainRecord>): RelayAnswer {
            enrols++
            sentKeys += clipboardPublicKey
            enrolRecords += records
            return enrolAnswers.removeFirstOrNull() ?: RelayAnswer.Unreachable
        }
        override suspend fun chainHead(): RelayChainHead? = head
        override suspend fun uploadChain(records: List<ChainRecord>, startOver: Boolean): RelayAnswer {
            uploads += records
            startOvers += startOver
            return uploadAnswer
        }
    }

    private fun jwt(claims: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"

    private class Rig(val relay: FakeRelay = FakeRelay(), gapSeconds: Long = 0L) {
        var token: String? = null
        var records: List<ChainRecord> = emptyList()
        var nowSeconds = 1000L
        val adopted = mutableListOf<String>()
        val service = DeviceIdentityService(
            relay = relay,
            idToken = { token },
            adoptToken = { adopted += it },
            authenticate = { data, _ -> data.copyOf(4) },
            publicKey = { "own-public-key" },
            chain = { records },
            nowSeconds = { nowSeconds },
            attemptGapSeconds = gapSeconds,
            newNonce = { "nonce-0123456789abcdef" }
        )
    }

    private fun ok(token: String) = RelayAnswer.Ok(JsonObject(mapOf("token" to JsonPrimitive(token))))
    private fun record(seq: Long, parent: String) =
        ChainRecord(seq, parent, if (seq == 1L) RecordKind.GENESIS else RecordKind.ADMIT, "a", "ka", "s$seq", "k$seq", "", "paired", "sig$seq")

    private fun chainOf(length: Int): List<ChainRecord> {
        val out = mutableListOf<ChainRecord>()
        for (seq in 1..length) out += record(seq.toLong(), out.lastOrNull()?.hash().orEmpty())
        return out
    }

    @Test
    fun enrolAdoptsTheCustomToken() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"owner1"}""")
        rig.relay.enrolAnswers += ok("custom-token")
        assertEquals(EnrolOutcome.ENROLLED, rig.service.ensureEnrolled("fx-self"))
        assertEquals(listOf("custom-token"), rig.adopted)
    }

    @Test
    fun anAlreadyEnrolledDeviceDoesNotCallTheRelay() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        assertEquals(EnrolOutcome.ALREADY, rig.service.ensureEnrolled("fx-self"))
        assertEquals(0, rig.relay.enrols)
    }

    @Test
    fun signedOutRefusedUnreachableAndConflictRetry() = runBlocking {
        val rig = Rig()
        assertEquals(EnrolOutcome.SIGNED_OUT, rig.service.ensureEnrolled("fx-self"))
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.key = null
        assertEquals(EnrolOutcome.UNREACHABLE, rig.service.ensureEnrolled("fx-self"))
        rig.relay.key = "k"
        rig.relay.enrolAnswers += RelayAnswer.Refused(403, "device was not admitted")
        assertEquals(EnrolOutcome.REFUSED, rig.service.ensureEnrolled("fx-self"))
        rig.relay.enrolAnswers += RelayAnswer.Refused(409, "state changed, retry")
        rig.relay.enrolAnswers += ok("after-conflict")
        assertEquals(EnrolOutcome.ENROLLED, rig.service.ensureEnrolled("fx-self"))
        assertEquals(listOf("after-conflict"), rig.adopted)
    }

    // 0.20.10 (one trust path): the relay decides by the signed chain; this device sends it the records it lacks.

    @Test
    fun onlyTheRecordsAfterTheRelaysHeadAreSent() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.records = chainOf(5)
        rig.relay.head = RelayChainHead(rig.records[0].hash(), 3L, rig.records[2].hash())
        assertEquals(SyncOutcome.SENT, rig.service.uploadChain())
        assertEquals(listOf(4L, 5L), rig.relay.uploads.single().map { it.seq })
    }

    @Test
    fun theWholeChainIsSentWhenTheRelayHoldsNoneOrAnotherRootOrAnUnknownHead() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.records = chainOf(3)
        rig.relay.head = RelayChainHead("", 0L, "")
        rig.service.uploadChain()
        rig.relay.head = RelayChainHead("other-root", 9L, "x")
        rig.service.uploadChain()
        rig.relay.head = RelayChainHead(rig.records[0].hash(), 9L, "unknown-head")
        rig.service.uploadChain()
        assertEquals(listOf(3, 3, 3), rig.relay.uploads.map { it.size })
    }

    @Test
    fun nothingIsSentWhenTheRelayIsUpToDateOrSignedOutOrThereIsNoChain() = runBlocking {
        val rig = Rig()
        rig.records = chainOf(2)
        assertEquals(SyncOutcome.SKIPPED, rig.service.uploadChain())
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.head = RelayChainHead(rig.records[0].hash(), 2L, rig.records[1].hash())
        assertEquals(SyncOutcome.SKIPPED, rig.service.uploadChain())
        rig.records = emptyList()
        assertEquals(SyncOutcome.SKIPPED, rig.service.uploadChain())
        assertTrue(rig.relay.uploads.isEmpty())
    }

    @Test
    fun aFailedUploadOrUnreachableRelayIsReported() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.records = chainOf(2)
        rig.relay.uploadAnswer = RelayAnswer.Refused(502, "identity update failed")
        assertEquals(SyncOutcome.FAILED, rig.service.uploadChain())
        rig.relay.head = null
        assertEquals(SyncOutcome.FAILED, rig.service.uploadChain())
    }

    @Test
    fun enrolCarriesTheChainSoANewDeviceIsKnownToTheRelay() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        rig.records = chainOf(4)
        rig.relay.enrolAnswers += ok("t")
        rig.service.ensureEnrolled("fx-self")
        assertEquals(rig.records, rig.relay.enrolRecords.single())
    }

    // 0.20.10: a device with no cloud record must be able to enrol, and the cloud stays closed only on a refusal.

    @Test
    fun enrolSendsTheDevicesOwnKey() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += ok("t")
        rig.service.ensureEnrolled("fx-self")
        assertEquals(listOf("own-public-key"), rig.relay.sentKeys)
    }

    @Test
    fun aRefusalInsideTheGapIsRememberedNotReportedAsEnrolled() = runBlocking {
        val rig = Rig(gapSeconds = 10 * 60L)
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += RelayAnswer.Refused(403, "device was not admitted")
        assertEquals(EnrolOutcome.REFUSED, rig.service.ensureEnrolled("fx-self"))
        rig.nowSeconds += 60
        assertEquals(EnrolOutcome.REFUSED, rig.service.ensureEnrolled("fx-self"))
        assertEquals(1, rig.relay.enrols)
        rig.relay.enrolAnswers += ok("after-pairing")
        assertEquals(EnrolOutcome.ENROLLED, rig.service.ensureEnrolled("fx-self", force = true))
        assertEquals(2, rig.relay.enrols)
    }

    @Test
    fun theGapEndsAfterTenMinutes() = runBlocking {
        val rig = Rig(gapSeconds = 10 * 60L)
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += RelayAnswer.Unreachable
        assertEquals(EnrolOutcome.UNREACHABLE, rig.service.ensureEnrolled("fx-self"))
        rig.nowSeconds += 10 * 60
        rig.relay.enrolAnswers += ok("later")
        assertEquals(EnrolOutcome.ENROLLED, rig.service.ensureEnrolled("fx-self"))
    }

    @Test
    fun aDeviceSessionIsAlreadyEnrolledEvenInsideTheGap() = runBlocking {
        val rig = Rig(gapSeconds = 10 * 60L)
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += RelayAnswer.Refused(403, "device was not admitted")
        rig.service.ensureEnrolled("fx-self")
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        assertEquals(EnrolOutcome.ALREADY, rig.service.ensureEnrolled("fx-self"))
    }

    @Test
    fun onlyARefusalOrAnotherSetupKeepsTheCloudClosed() {
        assertEquals(
            listOf(EnrolOutcome.REFUSED, EnrolOutcome.OTHER_SETUP),
            EnrolOutcome.entries.filter { !it.allowsCloud }
        )
    }

    // Start over: the relay holds an older setup of this account.

    @Test
    fun anEnrolThatMeetsAnotherSetupSaysSoAndIsNotRetriedAsARace() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += RelayAnswer.Refused(409, "other_setup")
        assertEquals(EnrolOutcome.OTHER_SETUP, rig.service.ensureEnrolled("fx-self"))
        assertEquals(1, rig.relay.enrols)
    }

    @Test
    fun anUploadThatMeetsAnotherSetupSaysSo() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.records = chainOf(2)
        rig.relay.uploadAnswer = RelayAnswer.Refused(409, "other_setup")
        assertEquals(SyncOutcome.OTHER_SETUP, rig.service.uploadChain())
    }

    @Test
    fun startOverSendsTheWholeChainAndAsksTheRelayToReplaceTheOldSetup() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        rig.records = chainOf(3)
        rig.relay.head = RelayChainHead("old-root", 9L, "old-head")
        assertEquals(SyncOutcome.SENT, rig.service.startOver())
        assertEquals(listOf(3), rig.relay.uploads.map { it.size })
        assertEquals(listOf(true), rig.relay.startOvers)
    }

    @Test
    fun startOverNeedsAChainAndASession() = runBlocking {
        val rig = Rig()
        rig.records = chainOf(2)
        assertEquals(SyncOutcome.SKIPPED, rig.service.startOver())
        rig.token = jwt("""{"sub":"o"}""")
        rig.records = emptyList()
        assertEquals(SyncOutcome.SKIPPED, rig.service.startOver())
        assertTrue(rig.relay.uploads.isEmpty())
    }

    @Test
    fun onlyANotAdmittedAnswerIsARefusalOtherRelayErrorsCountAsUnreachable() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        for (status in listOf(400, 401, 429, 500, 502, 503)) {
            rig.relay.enrolAnswers += RelayAnswer.Refused(status, "x")
            assertEquals("status $status", EnrolOutcome.UNREACHABLE, rig.service.ensureEnrolled("fx-self"))
        }
        for (status in listOf(403, 404)) {
            rig.relay.enrolAnswers += RelayAnswer.Refused(status, "x")
            assertEquals("status $status", EnrolOutcome.REFUSED, rig.service.ensureEnrolled("fx-self"))
        }
    }
}
