package com.fileapex.network

import com.fileapex.membership.ChainMember
import com.fileapex.membership.ChainState
import com.fileapex.membership.ChainVerdict
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.IdentityChangeAnswer
import com.fileapex.membership.IdentityChangeInbox
import com.fileapex.membership.IdentityChangeProof
import com.fileapex.membership.MembershipKeys
import com.fileapex.network.routes.IdentityChangeRouteHandlers
import com.fileapex.security.IdentityBinding
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeRouteHandlersTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = EcEventSigner("fx-old", pair.private, pair.public)
    private val member = ChainMember(signer.publicKey, MembershipKeys.pin(signer.publicKey), true)
    private val e2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(e2ee)
    private val inbox = IdentityChangeInbox(
        member = { if (it == "fx-old") member else null }, knownHardware = { "hw" }, oldIdAnswers = { false },
        confirm = { ChainVerdict.Accepted(ChainState.EMPTY) }, now = { 0L }, ttlMs = 60_000L
    )

    private fun body(oldId: String = "fx-old") = json.encodeToString(
        com.fileapex.membership.IdentityChangeRequest.serializer(),
        IdentityChangeProof.request(signer, oldId, newId, e2ee, "hw", "n1")
    )

    private fun answer(text: String) = json.decodeFromString(IdentityChangeAnswer.serializer(), text)

    @Test fun aRequestFromTheCertificatesDeviceWaitsAndAnswersWithTheCode() = runBlocking {
        val (status, text) = IdentityChangeRouteHandlers.receive(inbox, "fx-old", member.tlsPin, body(), json)
        assertEquals(200, status)
        assertEquals(IdentityChangeAnswer("waiting", IdentityChangeProof.code(json.decodeFromString(com.fileapex.membership.IdentityChangeRequest.serializer(), body())), ""), answer(text))
    }

    @Test fun aRequestForADifferentOldIdThanTheCertificateIsForbidden() = runBlocking {
        val (status, text) = IdentityChangeRouteHandlers.receive(inbox, "someone-else", member.tlsPin, body(), json)
        assertEquals(403, status)
        assertEquals("refused", answer(text).status)
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun aRefusedRequestSaysWhyAndNeverShowsAPrompt() = runBlocking {
        val (status, text) = IdentityChangeRouteHandlers.receive(inbox, "fx-old", "0".repeat(64), body(), json)
        assertEquals(403, status)
        assertEquals(IdentityChangeAnswer("refused", "", "WRONG_TLS_KEY"), answer(text))
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun brokenJsonIsABadRequestNotACrash() = runBlocking {
        assertEquals(400, IdentityChangeRouteHandlers.receive(inbox, "fx-old", member.tlsPin, "{nope", json).first)
    }

    @Test fun theStatusFollowsTheUsersAnswer() = runBlocking {
        IdentityChangeRouteHandlers.receive(inbox, "fx-old", member.tlsPin, body(), json)
        assertEquals("waiting", answer(IdentityChangeRouteHandlers.status(inbox, "fx-old", "fx-old", "n1", json)).status)
        inbox.accept("fx-old")
        assertEquals("confirmed", answer(IdentityChangeRouteHandlers.status(inbox, "fx-old", "fx-old", "n1", json)).status)
    }

    @Test fun onlyTheCertificatesDeviceMayAskAboutItsRequest() = runBlocking {
        IdentityChangeRouteHandlers.receive(inbox, "fx-old", member.tlsPin, body(), json)
        assertEquals("unknown", answer(IdentityChangeRouteHandlers.status(inbox, "fx-old", "someone-else", "n1", json)).status)
    }
}
