package com.fileapex.cloud.device

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
        var syncAnswer: RelayAnswer = RelayAnswer.Ok(JsonObject(emptyMap()))
        val syncs = mutableListOf<Pair<List<AdmitEntry>, List<RevokeEntry>>>()
        var enrols = 0
        override suspend fun relayKey() = key
        override suspend fun enrol(deviceId: String, ts: Long, nonce: String, tag: String): RelayAnswer {
            enrols++
            return enrolAnswers.removeFirstOrNull() ?: RelayAnswer.Unreachable
        }
        override suspend fun sync(admit: List<AdmitEntry>, revoke: List<RevokeEntry>): RelayAnswer {
            syncs += admit to revoke
            return syncAnswer
        }
    }

    private fun jwt(claims: String) = "h." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.toByteArray()) + ".s"

    private class Rig(val relay: FakeRelay = FakeRelay()) {
        var token: String? = null
        val adopted = mutableListOf<String>()
        val service = DeviceIdentityService(
            relay = relay,
            idToken = { token },
            adoptToken = { adopted += it },
            authenticate = { data, _ -> data.copyOf(4) },
            nowSeconds = { 1000L },
            newNonce = { "nonce-0123456789abcdef" }
        )
    }

    private fun ok(token: String) = RelayAnswer.Ok(JsonObject(mapOf("token" to JsonPrimitive(token))))
    private val admit = listOf(AdmitEntry("fx-peer", "0123456789abcdef", 50L))
    private val revoke = listOf(RevokeEntry("fx-gone", 40L))

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

    @Test
    fun aGoogleOnlySessionSendsNothing() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"sub":"o"}""")
        assertEquals(SyncOutcome.SKIPPED, rig.service.reconcile("fx-self", admit, revoke))
        assertTrue(rig.relay.syncs.isEmpty())
    }

    @Test
    fun identicalListsAreNotSentTwiceButChangedOnesAre() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        assertEquals(SyncOutcome.SENT, rig.service.reconcile("fx-self", admit, revoke))
        assertEquals(SyncOutcome.SKIPPED, rig.service.reconcile("fx-self", admit, revoke))
        assertEquals(1, rig.relay.syncs.size)
        assertEquals(SyncOutcome.SENT, rig.service.reconcile("fx-self", admit, emptyList()))
        assertEquals(2, rig.relay.syncs.size)
    }

    @Test
    fun aFailedSyncIsRetriedWithTheSameListsNextTime() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.relay.syncAnswer = RelayAnswer.Unreachable
        assertEquals(SyncOutcome.FAILED, rig.service.reconcile("fx-self", admit, revoke))
        rig.relay.syncAnswer = RelayAnswer.Ok(JsonObject(emptyMap()))
        assertEquals(SyncOutcome.SENT, rig.service.reconcile("fx-self", admit, revoke))
        assertEquals(2, rig.relay.syncs.size)
    }

    @Test
    fun enrollingAgainForgetsWhatWasSent() = runBlocking {
        val rig = Rig()
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.service.reconcile("fx-self", admit, revoke)
        rig.token = jwt("""{"sub":"o"}""")
        rig.relay.enrolAnswers += ok("fresh")
        rig.service.ensureEnrolled("fx-self")
        rig.token = jwt("""{"deviceId":"fx-self"}""")
        rig.service.reconcile("fx-self", admit, revoke)
        assertEquals(2, rig.relay.syncs.size)
    }
}
