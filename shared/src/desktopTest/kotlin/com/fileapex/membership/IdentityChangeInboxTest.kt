package com.fileapex.membership

import com.fileapex.membership.IdentityChangeInbox.Intake
import com.fileapex.membership.IdentityChangeInbox.Status
import com.fileapex.security.IdentityBinding
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeInboxTest {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val signer = EcEventSigner("fx-old", pair.private, pair.public)
    private val member = ChainMember(signer.publicKey, MembershipKeys.pin(signer.publicKey), true)
    private val e2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(e2ee)
    private var clock = 0L
    private val confirmed = mutableListOf<IdentityChangeRequest>()
    private var answers = false
    private var chainSays: ChainVerdict = ChainVerdict.Accepted(ChainState.EMPTY)

    private val inbox = IdentityChangeInbox(
        member = { if (it == "fx-old") member else null },
        knownHardware = { "hw" },
        oldIdAnswers = { answers },
        confirm = { confirmed += it; chainSays },
        now = { clock },
        ttlMs = 10 * 60_000L
    )

    private fun request(nonce: String = "n1", hardware: String = "hw") = IdentityChangeProof.request(signer, "fx-old", newId, e2ee, hardware, nonce)

    @Test fun aValidRequestWaitsForTheUserWithTheSharedCode() = runBlocking {
        val r = request()
        val intake = inbox.receive(r, member.tlsPin)
        assertEquals(Intake.Waiting(IdentityChangeProof.code(r)), intake)
        assertEquals(1, inbox.pending.value.size)
        assertEquals(Status.WAITING, inbox.status("fx-old", "n1"))
    }

    @Test fun aRefusedRequestIsNeverShownToTheUser() = runBlocking {
        val intake = inbox.receive(request(hardware = "other"), member.tlsPin)
        assertEquals(Intake.Refused(IdentityChangeProof.Refusal.HARDWARE_DIFFERS), intake)
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun anOldIdThatStillAnswersIsRefused() = runBlocking {
        answers = true
        assertEquals(Intake.Refused(IdentityChangeProof.Refusal.OLD_ID_ANSWERS), inbox.receive(request(), member.tlsPin))
    }

    @Test fun acceptingWritesTheChainOnceAndReportsConfirmed() = runBlocking {
        inbox.receive(request(), member.tlsPin)
        assertTrue(inbox.accept("fx-old") is ChainVerdict.Accepted)
        assertEquals(1, confirmed.size)
        assertEquals(Status.CONFIRMED, inbox.status("fx-old", "n1"))
        assertTrue(inbox.pending.value.isEmpty())
    }

    @Test fun decliningWritesNothing() = runBlocking {
        inbox.receive(request(), member.tlsPin)
        inbox.decline("fx-old")
        assertTrue(confirmed.isEmpty())
        assertEquals(Status.DECLINED, inbox.status("fx-old", "n1"))
    }

    @Test fun aNewRequestFromTheSameOldIdReplacesTheOldOne() = runBlocking {
        inbox.receive(request("n1"), member.tlsPin)
        inbox.receive(request("n2"), member.tlsPin)
        assertEquals(1, inbox.pending.value.size)
        assertEquals(Status.UNKNOWN, inbox.status("fx-old", "n1"))
        assertEquals(Status.WAITING, inbox.status("fx-old", "n2"))
    }

    @Test fun aRequestNobodyConfirmedExpires() = runBlocking {
        inbox.receive(request(), member.tlsPin)
        clock += 10 * 60_000L + 1
        assertEquals(Status.EXPIRED, inbox.status("fx-old", "n1"))
        assertTrue(inbox.pending.value.isEmpty())
        assertTrue(inbox.accept("fx-old") is ChainVerdict.Rejected)
        assertTrue(confirmed.isEmpty())
    }

    @Test fun aRefusedChainRecordIsReportedAndNotConfirmed() = runBlocking {
        chainSays = ChainVerdict.Rejected("no")
        inbox.receive(request(), member.tlsPin)
        assertTrue(inbox.accept("fx-old") is ChainVerdict.Rejected)
        assertEquals(Status.DECLINED, inbox.status("fx-old", "n1"))
    }
}
