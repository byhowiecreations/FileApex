package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `members` is a function of the chain and a few explicit local facts: these are its rules, one case each. */
class MemberProjectionTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")
    private val pin = "a".repeat(64)
    private val now = 1_000_000_000L
    private val minute = 60_000L

    private suspend fun chain(build: suspend MembershipChainLog.() -> Unit): ChainView {
        val log = MembershipChainLog(InMemoryChainStore()) { mac.id }
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.build()
        return log.view()
    }

    private fun project(view: ChainView, facts: Map<String, MemberFacts> = emptyMap(), legacyRemoved: Map<String, LegacyRemoval> = emptyMap()) =
        MemberProjection.project(view, facts, legacyRemoved, now).associateBy { it.deviceId }

    @Test
    fun aKeyedChainMemberIsActiveWithItsKey() = runBlocking {
        val view = chain { append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired") }
        val rows = project(view)
        assertEquals(ProjectedMember("phone", MemberState.ACTIVE, phone.key, "", ""), rows["phone"])
        assertEquals(MemberState.ACTIVE, rows["mac"]?.state)
    }

    @Test
    fun aKeyLessAdmitIsActiveWithNoKey() = runBlocking {
        val view = chain { append(mac.signer, RecordKind.ADMIT, "old", "legacy:old", pin, "paired") }
        assertEquals(ProjectedMember("old", MemberState.ACTIVE, "", "", ""), project(view)["old"])
    }

    @Test
    fun aRemovedMemberIsRemovedWithTheActorAndReasonOfTheRecord() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
        }
        assertEquals(ProjectedMember("phone", MemberState.REMOVED, "", "mac", "user_removed"), project(view)["phone"])
    }

    @Test
    fun aMemberWhoLeftIsRemovedWithTheLeaveReasonAndItselfAsActor() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(phone.signer, RecordKind.LEAVE, phone.id, phone.key, phone.pin, "left_cluster")
        }
        assertEquals(ProjectedMember("phone", MemberState.REMOVED, "", "phone", "left_cluster"), project(view)["phone"])
    }

    @Test
    fun aRemovedMemberAdmittedAgainUnderANewKeyIsActive() = runBlocking {
        val again = Dev("phone")
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
            append(mac.signer, RecordKind.ADMIT, phone.id, again.key, again.pin, "paired")
        }
        assertEquals(ProjectedMember("phone", MemberState.ACTIVE, again.key, "", ""), project(view)["phone"])
    }

    @Test
    fun aRecentInPersonPairingHoldsBackARemovalThatWasAlreadyInTheChain() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
        }
        val held = MemberFacts(pairedAtEpochMs = now - 5 * minute, pairedChainSeq = view.state.seq)
        assertEquals(ProjectedMember("phone", MemberState.ACTIVE, "", "", ""), project(view, mapOf("phone" to held))["phone"])
    }

    @Test
    fun theHoldEndsAfterTenMinutes() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
        }
        val old = MemberFacts(pairedAtEpochMs = now - 11 * minute, pairedChainSeq = view.state.seq)
        assertEquals(MemberState.REMOVED, project(view, mapOf("phone" to old))["phone"]?.state)
    }

    @Test
    fun aRemovalAfterThePairingIsNotHeldBack() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
        }
        val before = MemberFacts(pairedAtEpochMs = now - minute, pairedChainSeq = view.state.seq - 1)
        assertEquals(MemberState.REMOVED, project(view, mapOf("phone" to before))["phone"]?.state)
    }

    @Test
    fun aPendingAdmitNotOnTheChainIsActiveWithNoKey() = runBlocking {
        val view = chain { }
        val rows = project(view, mapOf("old" to MemberFacts(pendingAdmitAt = now - minute)))
        assertEquals(ProjectedMember("old", MemberState.ACTIVE, "", "", ""), rows["old"])
    }

    @Test
    fun aPendingAdmitOlderThanSevenDaysIsDropped() = runBlocking {
        val view = chain { }
        val rows = project(view, mapOf("old" to MemberFacts(pendingAdmitAt = now - 8 * 24 * 60 * minute)))
        assertNull(rows["old"])
    }

    @Test
    fun onceTheAdmitIsOnTheChainTheChainDecides() = runBlocking {
        val view = chain { append(mac.signer, RecordKind.ADMIT, "old", "legacy:old", pin, "paired") }
        assertEquals(ProjectedMember("old", MemberState.ACTIVE, "", "", ""), project(view, mapOf("old" to MemberFacts(pendingAdmitAt = now - minute)))["old"])
    }

    @Test
    fun anIdRemovedBeforeTheChainIsRemovedWithReasonLegacy() = runBlocking {
        val rows = project(chain { }, legacyRemoved = mapOf("ghost" to LegacyRemoval()))
        assertEquals(ProjectedMember("ghost", MemberState.REMOVED, "", "", "legacy"), rows["ghost"])
    }

    @Test
    fun aLegacyRemovalKeepsTheActorAndReasonItWasMadeWith() = runBlocking {
        val rows = project(chain { }, legacyRemoved = mapOf("twin" to LegacyRemoval("mac", "superseded")))
        assertEquals(ProjectedMember("twin", MemberState.REMOVED, "", "mac", "superseded"), rows["twin"])
    }

    @Test
    fun aPendingAdmitBeatsALegacyRemovalAndTheChainBeatsBoth() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
            append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, "user_removed")
        }
        val rows = project(view, mapOf("old" to MemberFacts(pendingAdmitAt = now - minute)), mapOf("old" to LegacyRemoval(), "phone" to LegacyRemoval()))
        assertEquals(MemberState.ACTIVE, rows["old"]?.state)
        assertEquals("user_removed", rows["phone"]?.eventReason)
    }

    @Test
    fun anIdOnNoInputHasNoRow() = runBlocking {
        assertNull(project(chain { }, mapOf("nobody" to MemberFacts()))["nobody"])
    }

    @Test
    fun theOutputIsOrderedByIdAndRepeatsExactly() = runBlocking {
        val view = chain {
            append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
            append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        }
        val first = MemberProjection.project(view, emptyMap(), mapOf("ghost" to LegacyRemoval()), now)
        assertEquals(first.map { it.deviceId }.sorted(), first.map { it.deviceId })
        assertEquals(first, MemberProjection.project(view, emptyMap(), mapOf("ghost" to LegacyRemoval()), now))
    }
}
