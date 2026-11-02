package com.fileapex.membership

import com.fileapex.membership.IdentityChangeRequester.State
import com.fileapex.security.IdentityBinding
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeRequesterTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = EcEventSigner("fx-new", pair.private, pair.public)
    private val e2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(e2ee)
    private val asked = mutableListOf<String>()
    private val statusAnswers = ArrayDeque<IdentityChangeAnswer?>()
    private var sendAnswer: (String) -> IdentityChangeAnswer? = { IdentityChangeAnswer("waiting", "123456") }
    private var pauses = 0

    private val link = object : IdentityChangeLink {
        override suspend fun send(peerId: String, request: IdentityChangeRequest): IdentityChangeAnswer? { asked += peerId; return sendAnswer(peerId) }
        override suspend fun status(peerId: String, oldId: String, nonce: String): IdentityChangeAnswer? = statusAnswers.removeFirstOrNull()
    }

    private fun requester(peers: List<String>) = IdentityChangeRequester(
        signer = signer, previousId = "fx-old", newId = newId, newE2eeKey = e2ee, hardwareHash = "hw",
        peers = { peers }, link = link, nonce = { "n1" }, pause = { pauses++ }, maxPolls = 5
    )

    @Test fun aMemberThatConfirmsEndsInDoneAndTheCodeWasShownOnTheWay() = runBlocking {
        statusAnswers += IdentityChangeAnswer("waiting"); statusAnswers += IdentityChangeAnswer("confirmed")
        val r = requester(listOf("mac"))
        val seen = mutableListOf<State>()
        r.run { seen += it }
        assertEquals(State.Done("mac"), r.state.value)
        assertTrue(seen.any { it is State.Asking && it.code == "123456" && it.peerId == "mac" })
    }

    @Test fun aRefusingMemberIsSkippedAndTheNextOneIsAsked() = runBlocking {
        sendAnswer = { if (it == "mac") IdentityChangeAnswer("refused", reason = "OLD_ID_ANSWERS") else IdentityChangeAnswer("waiting", "654321") }
        statusAnswers += IdentityChangeAnswer("confirmed")
        val r = requester(listOf("mac", "tablet"))
        r.run {}
        assertEquals(listOf("mac", "tablet"), asked)
        assertEquals(State.Done("tablet"), r.state.value)
    }

    @Test fun nobodyAnsweringEndsInFailedWithoutAskingTwice() = runBlocking {
        sendAnswer = { null }
        val r = requester(listOf("mac", "tablet"))
        r.run {}
        assertEquals(listOf("mac", "tablet"), asked)
        assertEquals(State.Failed(IdentityChangeRequester.Failure.NOBODY_ANSWERED), r.state.value)
    }

    @Test fun aDeclineEndsInFailedAndDoesNotAskOthers() = runBlocking {
        statusAnswers += IdentityChangeAnswer("declined")
        val r = requester(listOf("mac", "tablet"))
        r.run {}
        assertEquals(listOf("mac"), asked)
        assertEquals(State.Failed(IdentityChangeRequester.Failure.DECLINED), r.state.value)
    }

    @Test fun anExpiredRequestEndsInFailed() = runBlocking {
        statusAnswers += IdentityChangeAnswer("expired")
        val r = requester(listOf("mac"))
        r.run {}
        assertEquals(State.Failed(IdentityChangeRequester.Failure.EXPIRED), r.state.value)
    }

    @Test fun pollingStopsAfterTheLimit() = runBlocking {
        repeat(10) { statusAnswers += IdentityChangeAnswer("waiting") }
        val r = requester(listOf("mac"))
        r.run {}
        assertEquals(State.Failed(IdentityChangeRequester.Failure.EXPIRED), r.state.value)
        assertEquals(5, pauses)
    }

    @Test fun noPeersAtAllIsAFailureNotAHang() = runBlocking {
        val r = requester(emptyList())
        r.run {}
        assertEquals(State.Failed(IdentityChangeRequester.Failure.NOBODY_ANSWERED), r.state.value)
    }
}
