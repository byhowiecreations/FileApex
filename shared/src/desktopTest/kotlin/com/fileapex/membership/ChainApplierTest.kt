package com.fileapex.membership

import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainApplierTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val mac = Dev("mac")
    private val phone = Dev("phone")
    private val tablet = Dev("tablet")

    private class Effects(val known: MutableMap<String, String> = mutableMapOf(), val clock: () -> Long = { 0L }) : ChainEffects {
        private val holds = mutableMapOf<String, MemberFacts>()
        var refreshes = 0
        override suspend fun refreshRoster() { refreshes++ }
        override suspend fun holdRemoval(deviceId: String, chainSeq: Long) { holds[deviceId] = MemberFacts(pairedAtEpochMs = clock(), pairedChainSeq = chainSeq) }
        override suspend fun removalHeld(deviceId: String, removalSeq: Long): Boolean = MemberProjection.held(holds[deviceId], removalSeq, clock())
        val pinsSet = mutableListOf<Pair<String, String>>()
        val removals = mutableListOf<Pair<String, String>>()
        var handledHash = ""
        override suspend fun knownPin(deviceId: String): String? = known[deviceId]
        override suspend fun setPin(deviceId: String, pin: String) { pinsSet += deviceId to pin; known[deviceId] = pin }
        override suspend fun selfRemoved(by: String, recordHash: String) { removals += by to recordHash }
        override suspend fun handledRemoval(): String = handledHash
        override suspend fun markHandled(recordHash: String) { handledHash = recordHash }
        val removedLocally = mutableSetOf<String>()
        val marked = mutableListOf<Triple<String, String, String>>()
        override suspend fun knownRemoved(deviceId: String): Boolean = deviceId in removedLocally
        val ensured = mutableListOf<String>()
        override suspend fun ensureProfile(deviceId: String) { ensured += deviceId }
        val markedPins = mutableMapOf<String, String>()
        override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) { marked += Triple(deviceId, by, reason); markedPins[deviceId] = tlsPin; removedLocally += deviceId }
        var keySeq: Long? = 0L
        val rotations = mutableListOf<Long>()
        override suspend fun clusterKeySeq(): Long? = keySeq
        override suspend fun rotateClusterKey(chainSeq: Long) { rotations += chainSeq; keySeq = chainSeq }
    }

    private suspend fun chain(): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        return log
    }

    @Test
    fun anActiveMembersPinThatDiffersFromTheStoredOneIsSetSilently() = runBlocking {
        val log = chain()
        val fx = Effects(mutableMapOf("phone" to "ee".repeat(32)))
        ChainApplier("mac", fx).apply(log.view())
        assertEquals(listOf("phone" to phone.pin), fx.pinsSet)
    }

    private suspend fun chainWithLegacyPhone(): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        log.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        log.append(mac.signer, RecordKind.ADMIT, "old", "legacy:old", "ab".repeat(32), "paired")
        return log
    }

    @Test
    fun aLegacyMembersChainPinNeverReplacesAPinThisDeviceAlreadyTrusts() = runBlocking {
        val fx = Effects(mutableMapOf("old" to "cd".repeat(32)))
        ChainApplier("mac", fx).apply(chainWithLegacyPhone().view())
        assertTrue("the chain copy of a pin the member never signed has no authority", fx.pinsSet.isEmpty())
        assertEquals("cd".repeat(32), fx.known["old"])
    }

    @Test
    fun aLegacyMembersChainPinIsUsedWhenNoPinIsKnownYet() = runBlocking {
        val fx = Effects()
        ChainApplier("mac", fx).apply(chainWithLegacyPhone().view())
        assertEquals(listOf("old" to "ab".repeat(32)), fx.pinsSet)
    }

    @Test
    fun aPinThatAlreadyMatchesAndOneselfAreLeftAlone() = runBlocking {
        val log = chain()
        val fx = Effects(mutableMapOf("phone" to phone.pin, "mac" to "ee".repeat(32)))
        ChainApplier("mac", fx).apply(log.view())
        assertTrue(fx.pinsSet.isEmpty())
    }

    @Test
    fun aMembersNewPinRecordReachesThePinStore() = runBlocking {
        val log = chain()
        val newPin = "cd".repeat(32)
        log.append(phone.signer, RecordKind.TLS_PIN, phone.id, phone.key, newPin, "tls_pin")
        val fx = Effects(mutableMapOf("phone" to phone.pin))
        ChainApplier("mac", fx).apply(log.view())
        assertEquals(listOf("phone" to newPin), fx.pinsSet)
    }

    @Test
    fun beingRemovedByAMemberIsReportedOnceForThatRecord() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects()
        val applier = ChainApplier("phone", fx)
        applier.apply(log.view())
        applier.apply(log.view())
        assertEquals(1, fx.removals.size)
        assertEquals("mac", fx.removals.single().first)
    }

    @Test
    fun leavingOnOwnAccountIsNotReportedAsARemoval() = runBlocking {
        val log = chain()
        log.append(phone.signer, RecordKind.LEAVE, phone.id, phone.key, phone.pin, MembershipReason.LEFT_CLUSTER)
        val fx = Effects()
        ChainApplier("phone", fx).apply(log.view())
        assertTrue(fx.removals.isEmpty())
    }

    @Test
    fun aDeviceNotInTheChainDoesNothing() = runBlocking {
        val log = chain()
        val fx = Effects()
        ChainApplier("tablet", fx).apply(log.view())
        assertTrue(fx.removals.isEmpty())
    }

    @Test
    fun aRemovalOnALosingBranchIsNeverActedOn() = runBlocking {
        val a = chain()
        val b = MembershipChainLog(InMemoryChainStore()).also { it.ingest(a.recordsAfter(0L)) }
        a.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        b.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        a.ingest(b.recordsAfter(0L))
        val removalWins = a.view()
        val fx = Effects()
        ChainApplier("phone", fx).apply(removalWins)
        assertEquals(1, fx.removals.size)
        val onlyAdmit = MembershipChainLog(InMemoryChainStore()).also { it.ingest(a.recordsAfter(0L).filter { r -> r.kind != RecordKind.REMOVE }) }
        val fx2 = Effects()
        ChainApplier("phone", fx2).apply(onlyAdmit.view())
        assertTrue(fx2.removals.isEmpty())
    }

    @Test
    fun aMemberTheChainRemovedIsRemovedFromThisDevicesRosterOnce() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects()
        val applier = ChainApplier("tablet", fx)
        applier.apply(log.view())
        applier.apply(log.view())
        assertEquals(listOf(Triple("phone", "mac", MembershipReason.USER_REMOVED)), fx.marked)
    }

    @Test
    fun aDeviceJustPairedInPersonIsNotRemovedAgainByTheOlderRemovalWhileItJoins() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        var clock = 1_000L
        val fx = Effects(clock = { clock })
        val applier = ChainApplier("tablet", fx)
        applier.pairedInPerson("phone", log.view().state.seq)
        applier.apply(log.view())
        assertTrue("the removal was applied during the grace", fx.marked.isEmpty())
        clock += 30L * 60_000L
        applier.apply(log.view())
        assertEquals("the removal applies once the grace is over", 1, fx.marked.size)
    }

    @Test
    fun aRemovalOrLeaveThatCameAfterThePairingIsNewsAndIsAppliedAtOnce() = runBlocking {
        val log = chain()
        val fx = Effects(clock = { 1_000L })
        val applier = ChainApplier("tablet", fx)
        applier.pairedInPerson("phone", log.view().state.seq)
        log.append(phone.signer, RecordKind.LEAVE, phone.id, phone.key, phone.pin, MembershipReason.LEFT_CLUSTER)
        applier.apply(log.view())
        assertEquals("a leave made after the pairing was held back", 1, fx.marked.size)
    }

    @Test
    fun everyWalkOfTheChainRebuildsTheRosterOnce() = runBlocking {
        val fx = Effects()
        val applier = ChainApplier("tablet", fx)
        applier.apply(chain().view())
        applier.apply(chain().view())
        assertEquals(2, fx.refreshes)
    }

    @Test
    fun aRemovalStillAppliesToADeviceThatWasNeverPairedInPerson() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects()
        ChainApplier("tablet", fx).apply(log.view())
        assertEquals(1, fx.marked.size)
    }

    @Test
    fun aMemberWhoLeftIsRemovedFromTheRosterToo() = runBlocking {
        val log = chain()
        log.append(phone.signer, RecordKind.LEAVE, phone.id, phone.key, phone.pin, MembershipReason.LEFT_CLUSTER)
        val fx = Effects()
        ChainApplier("mac", fx).apply(log.view())
        assertEquals(listOf(Triple("phone", "phone", MembershipReason.LEFT_CLUSTER)), fx.marked)
    }

    @Test
    fun thisDeviceIsNeverMarkedRemovedByTheRosterRule() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects()
        ChainApplier("phone", fx).apply(log.view())
        assertTrue(fx.marked.isEmpty())
    }

    @Test
    fun aRemovalThePeerRosterAlreadyHasIsNotWrittenAgain() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects().also { it.removedLocally += "phone" }
        ChainApplier("tablet", fx).apply(log.view())
        assertTrue(fx.marked.isEmpty())
    }

    @Test
    fun aRemovalCarriesTheRemovedMembersTlsPinSoItsConnectionIsRecognisedAsRevoked() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val fx = Effects()
        ChainApplier("mac", fx).apply(log.view())
        assertEquals(phone.pin, fx.markedPins["phone"])
    }

    @Test
    fun everyActiveMemberOtherThanThisDeviceGetsAProfileSoItShowsInTheDeviceList() = runBlocking {
        val log = chain()
        log.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
        log.append(mac.signer, RecordKind.REMOVE, tablet.id, tablet.key, tablet.pin, MembershipReason.USER_REMOVED)
        val fx = Effects(mutableMapOf("phone" to phone.pin))
        ChainApplier("mac", fx).apply(log.view())
        assertEquals(listOf("phone"), fx.ensured)
    }

    // 0.20.10 (one trust path): the cloud record key is rotated by the device responsible for a removal, once per removal.

    private suspend fun threeDevices(): MembershipChainLog = chain().also {
        it.append(mac.signer, RecordKind.ADMIT, tablet.id, tablet.key, tablet.pin, "paired")
    }

    @Test
    fun theAuthorOfARemovalRotatesTheClusterKeyOnceAtThatRecord() = runBlocking {
        val log = threeDevices()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val effects = Effects()
        val applier = ChainApplier(mac.id, effects)
        applier.apply(log.view())
        applier.apply(log.view())
        assertEquals(listOf(log.view().state.seq), effects.rotations)
    }

    @Test
    fun aDeviceThatDidNotMakeTheRemovalDoesNotRotate() = runBlocking {
        val log = threeDevices()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val effects = Effects()
        ChainApplier(tablet.id, effects).apply(log.view())
        assertEquals(emptyList<Long>(), effects.rotations)
    }

    @Test
    fun aLeaveIsRotatedForByTheLowestActiveId() = runBlocking {
        val log = threeDevices()
        log.append(tablet.signer, RecordKind.LEAVE, tablet.id, tablet.key, tablet.pin, MembershipReason.LEFT_CLUSTER)
        val lowest = Effects()
        ChainApplier(mac.id, lowest).apply(log.view())
        val other = Effects()
        ChainApplier(phone.id, other).apply(log.view())
        assertEquals(listOf(log.view().state.seq), lowest.rotations)
        assertEquals(emptyList<Long>(), other.rotations)
    }

    @Test
    fun aKeyAlreadyMadeAtOrAfterTheRemovalIsNotRotatedAgainAndNoKeyMeansNoRotation() = runBlocking {
        val log = threeDevices()
        log.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val newer = Effects().apply { keySeq = log.view().state.seq + 3 }
        ChainApplier(mac.id, newer).apply(log.view())
        val none = Effects().apply { keySeq = null }
        ChainApplier(mac.id, none).apply(log.view())
        assertEquals(emptyList<Long>(), newer.rotations)
        assertEquals(emptyList<Long>(), none.rotations)
    }
}

