package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pairing completion ticks the chain at once so the joiner is admitted without waiting for the minute schedule. */
class PairingChainTickTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val amanda = Dev("amanda")
    private val mac = Dev("mac")
    private val phone = Dev("phone")

    private class Book(val self: String) : ChainProfiles {
        override suspend fun own(): PeerNodeState = PeerNodeState(deviceId = self, deviceName = "name-$self", port = 8080)
        override suspend fun outgoing(): List<PeerNodeState> = listOf(own())
        override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
    }

    private class Net {
        val ticks = mutableMapOf<String, ChainTick>()
        val hubs = mutableMapOf<String, MembershipHub>()
        fun transportFor(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? =
                ticks[peerId]?.replicator?.receive(self, records, profiles)
            override suspend fun pull(peerId: String): ChainAnswer? = ticks[peerId]?.replicator?.serve()
            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? =
                ticks[peerId]?.receiveJoin(self, key, tlsPin, ticks.keys, profile)
        }
    }

    private fun node(net: Net, dev: Dev, roster: suspend () -> Set<String>): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor(dev.id)
        val apply = suspend {}
        val replicator = ChainReplicator(log, transport, onChanged = apply, profiles = Book(dev.id))
        val tick = ChainTick(dev.id, log, replicator, transport, ChainBootstrap(log, { dev.signer }), { dev.signer }, onChanged = apply)
        net.ticks[dev.id] = tick
        net.hubs[dev.id] = MembershipHub(log, replicator, tick, roster, applyChain = apply)
        return log
    }

    @Test
    fun aJoinerThatJustPairedIsAdmittedOnOnPairedWithoutAScheduledTick() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("amanda", "mac") }
        node(net, amanda) { setOf("amanda", "mac") }
        val phoneLog = node(net, phone) { setOf("phone", "mac") }
        net.hubs.getValue("mac").onPaired("amanda", mac.pin)
        net.hubs.getValue("amanda").onPaired("mac", amanda.pin)
        repeat(3) {
            net.ticks.getValue("mac").tick(setOf("amanda", "mac"), setOf("amanda"), mac.pin)
            net.ticks.getValue("amanda").tick(setOf("amanda", "mac"), setOf("mac"), amanda.pin)
        }
        assertTrue("mac" in macLog.view().state.activeIds())
        net.hubs.getValue("phone").onPaired("mac", phone.pin)
        assertTrue("phone" in macLog.view().state.activeIds())
        assertTrue("phone" in phoneLog.view().state.activeIds())
    }

    private class Rekeyable(val id: String) {
        var current = Dev(id)
        var renewals = 0
    }

    private suspend fun removedPhoneSetup(net: Net, marks: PairingMarks = InMemoryPairingMarks(), clock: () -> Long): Triple<MembershipChainLog, MembershipChainLog, Rekeyable> {
        val macLog = node(net, mac) { setOf("mac", "phone") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        macLog.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        macLog.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val holder = Rekeyable("phone").also { it.current = phone }
        val phoneLog = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor("phone")
        val apply = suspend {}
        val replicator = ChainReplicator(phoneLog, transport, onChanged = apply, profiles = Book("phone"))
        val tick = ChainTick(
            "phone", phoneLog, replicator, transport, ChainBootstrap(phoneLog, { holder.current.signer }), { holder.current.signer },
            renewSigningKey = { holder.renewals++; holder.current = Dev("phone") }, now = clock, onChanged = apply, marks = marks
        )
        net.ticks["phone"] = tick
        net.hubs["phone"] = MembershipHub(phoneLog, replicator, tick, { setOf("phone", "mac") }, applyChain = apply)
        return Triple(macLog, phoneLog, holder)
    }

    @Test
    fun aDeviceTheChainRemovedTakesANewSigningKeyWhenThePersonPairsItAgainAndIsAdmitted() = runBlocking {
        val net = Net()
        val (macLog, _, holder) = removedPhoneSetup(net, clock = { 1_000L })
        net.hubs.getValue("phone").onPaired("mac", holder.current.pin)
        assertTrue("phone asked again with its new key", macLog.view().state.member("phone")!!.active)
        assertTrue(macLog.view().state.member("phone")!!.key == holder.current.key)
        assertTrue(holder.renewals == 1)
    }

    @Test
    fun theMarkOfAnInPersonPairingSurvivesARestartOfTheDevice() = runBlocking {
        val net = Net()
        val marks = InMemoryPairingMarks()
        val (macLog, phoneLog, holder) = removedPhoneSetup(net, marks) { 1_000L }
        net.ticks.getValue("phone").notePairedInPerson()
        val transport = net.transportFor("phone")
        val replicator = ChainReplicator(phoneLog, transport, onChanged = {}, profiles = Book("phone"))
        val restarted = ChainTick(
            "phone", phoneLog, replicator, transport, ChainBootstrap(phoneLog, { holder.current.signer }), { holder.current.signer },
            renewSigningKey = { holder.renewals++; holder.current = Dev("phone") }, now = { 1_000L }, onChanged = {}, marks = marks
        )
        net.ticks["phone"] = restarted
        restarted.tick(setOf("mac", "phone"), setOf("mac"), holder.current.pin)
        assertTrue("the restarted device still knew it was paired in person", holder.renewals == 1)
        assertTrue(macLog.view().state.member("phone")!!.active)
    }

    @Test
    fun aPairingThatHappenedLongAgoNeverLetsARemovedDeviceTakeANewKey() = runBlocking {
        val net = Net()
        var clock = 1_000L
        val (macLog, _, holder) = removedPhoneSetup(net, clock = { clock })
        net.ticks.getValue("phone").notePairedInPerson()
        clock += 60L * 60_000L
        net.ticks.getValue("phone").tick(setOf("mac", "phone"), setOf("mac"), holder.current.pin)
        assertTrue(holder.renewals == 0)
        assertTrue(!macLog.view().state.member("phone")!!.active)
    }

    @Test
    fun aJoinerWithAnotherRootDropsItsClusterOnceAndTakesTheHostsChain() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("mac") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        val phoneLog = node(net, phone) { setOf("phone") }
        phoneLog.append(phone.signer, RecordKind.GENESIS, phone.id, phone.key, phone.pin, "paired")
        phoneLog.append(phone.signer, RecordKind.ADMIT, amanda.id, amanda.key, amanda.pin, "paired")
        var replaced = 0
        assertTrue(net.ticks.getValue("phone").replicator.joinClusterOf("mac") { replaced++ })
        assertTrue(replaced == 1)
        assertTrue(phoneLog.view().state.activeIds() == listOf("mac"))
        assertTrue(phoneLog.view().canonical.first().actor == "mac")
    }

    @Test
    fun aJoinerInAClusterKeepsItWhenTheHostIsSoloWithNoChainAndTheSoloDeviceThenJoinsIt() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("mac", "phone") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        macLog.append(mac.signer, RecordKind.ADMIT, amanda.id, amanda.key, amanda.pin, "paired")
        val phoneLog = node(net, phone) { setOf("phone", "mac") }
        var replaced = 0
        assertTrue(net.ticks.getValue("mac").replicator.joinClusterOf("phone") { replaced++ })
        assertTrue("the joiner's own cluster stays", replaced == 0)
        assertTrue(macLog.view().state.activeIds().sorted() == listOf("amanda", "mac"))

        net.hubs.getValue("mac").onPaired("phone", mac.pin)
        net.hubs.getValue("phone").onPaired("mac", phone.pin)
        repeat(3) {
            net.ticks.getValue("phone").tick(setOf("phone", "mac"), setOf("mac"), phone.pin)
            net.ticks.getValue("mac").tick(setOf("mac", "phone", "amanda"), setOf("phone"), mac.pin)
        }
        assertTrue("the solo device joined the existing cluster", phoneLog.view().state.activeIds().sorted() == listOf("amanda", "mac", "phone"))
        assertTrue(macLog.view().state.activeIds().sorted() == listOf("amanda", "mac", "phone"))
    }

    @Test
    fun aJoinerWithTheSameRootOrNoChainDropsNothing() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("mac") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        macLog.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        val phoneLog = node(net, phone) { setOf("phone") }
        var replaced = 0
        assertTrue(net.ticks.getValue("phone").replicator.joinClusterOf("mac") { replaced++ })
        assertTrue(phoneLog.view().state.member("phone")!!.active)
        assertTrue(net.ticks.getValue("phone").replicator.joinClusterOf("mac") { replaced++ })
        assertTrue(replaced == 0)
    }

    @Test
    fun theJoinerSideOfAPairingLetsGoOfItsOldClusterExceptTheHostAndTakesTheHostsChain() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("mac") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        macLog.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        val phoneLog = MembershipChainLog(InMemoryChainStore())
        phoneLog.append(phone.signer, RecordKind.GENESIS, phone.id, phone.key, phone.pin, "paired")
        phoneLog.append(phone.signer, RecordKind.ADMIT, amanda.id, amanda.key, amanda.pin, "paired")
        val transport = net.transportFor("phone")
        val apply = suspend {}
        val replicator = ChainReplicator(phoneLog, transport, onChanged = apply, profiles = Book("phone"))
        val tick = ChainTick("phone", phoneLog, replicator, transport, ChainBootstrap(phoneLog, { phone.signer }), { phone.signer }, onChanged = apply)
        net.ticks["phone"] = tick
        var kept = ""
        MembershipHub(phoneLog, replicator, tick, { setOf("phone", "mac") }, replaceCluster = { keep -> kept = keep }, applyChain = apply).onJoinedHost("mac", phone.pin)
        assertTrue(kept == "mac")
        assertTrue(phoneLog.view().state.member("mac")!!.active)
        assertTrue(phoneLog.view().state.member("phone")!!.active)
        assertTrue(phoneLog.view().state.member("amanda") == null)
    }

    private class CountingEffects : ChainEffects {
        override suspend fun refreshRoster() = Unit
        override suspend fun holdRemoval(deviceId: String, chainSeq: Long) = Unit
        override suspend fun removalHeld(deviceId: String, removalSeq: Long) = false
        var selfRemovals = 0
        private var handled = ""
        override suspend fun knownPin(deviceId: String): String? = null
        override suspend fun setPin(deviceId: String, pin: String) {}
        override suspend fun ensureProfile(deviceId: String) {}
        override suspend fun selfRemoved(by: String, recordHash: String) { handled = recordHash; selfRemovals++ }
        override suspend fun handledRemoval(): String = handled
        override suspend fun markHandled(recordHash: String) { handled = recordHash }
        override suspend fun knownRemoved(deviceId: String): Boolean = false
        override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) {}
    }

    @Test
    fun aJoinerThatTakesAChainWithItsOwnOldRemovalIsNotWipedByTheApplierThatRunsOnEveryChange() = runBlocking {
        val net = Net()
        val macLog = node(net, mac) { setOf("mac") }
        macLog.append(mac.signer, RecordKind.GENESIS, mac.id, mac.key, mac.pin, "paired")
        macLog.append(mac.signer, RecordKind.ADMIT, phone.id, phone.key, phone.pin, "paired")
        macLog.append(mac.signer, RecordKind.REMOVE, phone.id, phone.key, phone.pin, MembershipReason.USER_REMOVED)
        val phoneLog = MembershipChainLog(InMemoryChainStore())
        val effects = CountingEffects()
        val applier = ChainApplier("phone", effects)
        val transport = net.transportFor("phone")
        // The same wiring as the app: every change to the chain runs the applier.
        val replicator = ChainReplicator(phoneLog, transport, onChanged = { applier.apply(phoneLog.view()) }, profiles = Book("phone"))
        val tick = ChainTick("phone", phoneLog, replicator, transport, ChainBootstrap(phoneLog, { phone.signer }), { phone.signer }, onChanged = { applier.apply(phoneLog.view()) })
        net.ticks["phone"] = tick
        MembershipHub(
            phoneLog, replicator, tick, { setOf("phone", "mac") },
            acknowledgeHistory = { applier.acknowledgeHistory(phoneLog.view()) },
            applyChain = { applier.apply(phoneLog.view()) }
        ).onJoinedHost("mac", phone.pin)
        assertTrue("an old removal was applied as a kick: ${effects.selfRemovals}", effects.selfRemovals == 0)
    }
}
