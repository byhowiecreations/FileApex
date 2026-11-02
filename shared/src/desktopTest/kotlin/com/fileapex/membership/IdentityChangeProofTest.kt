package com.fileapex.membership

import com.fileapex.membership.IdentityChangeProof.Refusal
import com.fileapex.security.IdentityBinding
import java.security.KeyPairGenerator
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityChangeProofTest {
    private class Dev(var id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val key = MembershipKeys.encode(pair.public)
        val pin = MembershipKeys.pin(key)
        fun signer() = EcEventSigner(id, pair.private, pair.public)
    }

    private fun e2eeKey(): String = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)

    private val mac = Dev("mac")
    private val honor = Dev("fx-old")
    private val newE2ee = e2eeKey()
    private val newId = IdentityBinding.deriveDeviceId(newE2ee)

    private fun genesis(by: Dev) = ChainRecords.sign(by.signer(), ChainRecord(1, "", RecordKind.GENESIS, by.id, by.key, by.id, by.key, by.pin, "paired"))
    private fun record(state: ChainState, kind: RecordKind, by: Dev, subject: String, subjectKey: String, pin: String, reason: String) =
        ChainRecords.sign(by.signer(), ChainRecord(state.seq + 1, state.head, kind, by.id, by.key, subject, subjectKey, pin, reason))

    private fun chain(): ChainState {
        val s1 = (MembershipChain.apply(ChainState.EMPTY, genesis(mac)) as ChainVerdict.Accepted).state
        return (MembershipChain.apply(s1, record(s1, RecordKind.ADMIT, mac, honor.id, honor.key, honor.pin, "paired")) as ChainVerdict.Accepted).state
    }

    private fun request(nonce: String = "n1", hardware: String = "hw") =
        IdentityChangeProof.request(honor.signer(), honor.id, newId, newE2ee, hardware, nonce)

    private fun verify(r: IdentityChangeRequest = request(), state: ChainState = chain(), pin: String = honor.pin, hw: String = "hw", answers: Boolean = false) =
        IdentityChangeProof.verify(r, state.member(r.oldId), pin, hw, answers)

    @Test fun theSameDeviceWithANewIdIsAccepted() = assertNull(verify())

    @Test fun anUnknownOldIdIsRefused() = assertEquals(Refusal.NOT_A_MEMBER, verify(state = ChainState.EMPTY))

    @Test fun aSignatureFromAnotherKeyIsRefused() {
        val thief = Dev("fx-old")
        val forged = IdentityChangeProof.request(thief.signer(), honor.id, newId, newE2ee, "hw", "n1")
        assertEquals(Refusal.WRONG_KEY, verify(forged))
    }

    @Test fun aTamperedRequestFailsItsSignature() {
        val ok = request()
        assertEquals(Refusal.BAD_SIGNATURE, verify(IdentityChangeRequest(ok.oldId, ok.newId, ok.newE2eeKey, ok.signingKey, "other-hw", ok.nonce, ok.signature)))
    }

    @Test fun aNewIdThatIsNotDerivedFromTheNewKeyIsRefused() {
        val r = IdentityChangeProof.request(honor.signer(), honor.id, "fx-0123456789abcdef0123456789abcdef", newE2ee, "hw", "n1")
        assertEquals(Refusal.ID_NOT_FROM_KEY, verify(r))
    }

    @Test fun anUnchangedIdIsNotAChange() {
        val r = IdentityChangeProof.request(honor.signer(), honor.id, honor.id, newE2ee, "hw", "n1")
        assertEquals(Refusal.SAME_ID, verify(r))
    }

    @Test fun aConnectionOnAnotherTlsKeyIsRefused() = assertEquals(Refusal.WRONG_TLS_KEY, verify(pin = Dev("x").pin))

    @Test fun otherHardwareIsRefused() = assertEquals(Refusal.HARDWARE_DIFFERS, verify(hw = "different"))

    @Test fun anOldIdThatStillAnswersMeansTwoDevicesNotAChange() = assertEquals(Refusal.OLD_ID_ANSWERS, verify(answers = true))

    @Test fun bothScreensShowTheSameCodeAndItDependsOnTheRequest() {
        val a = request("n1"); val b = request("n2")
        assertEquals(IdentityChangeProof.code(a), IdentityChangeProof.code(a))
        assertEquals(6, IdentityChangeProof.code(a).length)
        assertNotEquals(IdentityChangeProof.code(a), IdentityChangeProof.code(b))
    }

    @Test fun theChainTakesTheChangeAndTheNewIdActsWithTheSameKey() {
        val r = request()
        var s = chain()
        s = (MembershipChain.apply(s, record(s, RecordKind.REMOVE, mac, r.oldId, honor.key, honor.pin, MembershipReason.USER_REMOVED)) as ChainVerdict.Accepted).state
        s = (MembershipChain.apply(s, record(s, RecordKind.ADMIT, mac, r.newId, r.signingKey, honor.pin, MembershipReason.PAIRED)) as ChainVerdict.Accepted).state
        assertEquals(setOf("mac", newId), s.activeIds().toSet())
        val announce = ChainRecords.sign(
            EcEventSigner(newId, honor.pair.private, honor.pair.public),
            ChainRecord(s.seq + 1, s.head, RecordKind.TLS_PIN, newId, honor.key, newId, honor.key, honor.pin, MembershipReason.TLS_PIN)
        )
        assertTrue(MembershipChain.apply(s, announce) is ChainVerdict.Accepted)
    }
}
