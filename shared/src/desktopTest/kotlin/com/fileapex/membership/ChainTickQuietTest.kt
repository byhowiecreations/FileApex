package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** With the state field read from health probes, a tick makes no chain request while the chains are equal. */
class ChainTickQuietTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val devs = listOf(Dev("amanda"), Dev("mac"), Dev("phone")).associateBy { it.id }
    private val roster = devs.keys
    private val pins = devs.mapValues { it.value.pin }

    private class Node(val dev: Dev, val log: MembershipChainLog, val tick: ChainTick, val source: ChainStateSource, val watch: ChainStateWatch)

    private class Net {
        val nodes = mutableMapOf<String, Node>()
        val offline = mutableSetOf<String>()
        var pulls = 0
        var clock = 10_000_000L
        val wipes = mutableMapOf<String, MutableList<String>>()
        fun transportFor(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? =
                if (peerId in offline || peerId !in nodes) null else nodes.getValue(peerId).tick.replicator.receive(self, records, profiles)

            override suspend fun pull(peerId: String): ChainAnswer? {
                if (peerId in offline || peerId !in nodes) return null
                pulls++
                return nodes.getValue(peerId).tick.replicator.serve()
            }

            override suspend fun pull(peerId: String, since: String): ChainAnswer? {
                if (peerId in offline || peerId !in nodes) return null
                pulls++
                return nodes.getValue(peerId).tick.replicator.serve(since = since)
            }

            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? =
                if (peerId in offline || peerId !in nodes) null else nodes.getValue(peerId).tick.receiveJoin(self, key, tlsPin, nodes.keys, profile)
        }
    }

    private class Effects(private val wipes: MutableList<String>) : ChainEffects {
        private var handled = ""
        override suspend fun refreshRoster() = Unit
        override suspend fun holdRemoval(deviceId: String, chainSeq: Long) = Unit
        override suspend fun removalHeld(deviceId: String, removalSeq: Long) = false
        override suspend fun knownPin(deviceId: String): String? = null
        override suspend fun setPin(deviceId: String, pin: String) {}
        override suspend fun ensureProfile(deviceId: String) {}
        override suspend fun selfRemoved(by: String, recordHash: String) { wipes += by }
        override suspend fun handledRemoval(): String = handled
        override suspend fun markHandled(recordHash: String) { handled = recordHash }
        override suspend fun knownRemoved(deviceId: String): Boolean = false
        override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) {}
    }

    private fun profileOf(id: String) = PeerNodeState(deviceId = id, deviceName = "name-$id", port = 8080, publicKey = "e2ee-$id", tlsPort = 8443)

    private fun node(net: Net, dev: Dev): Node {
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor(dev.id)
        val book = object : ChainProfiles {
            override suspend fun own() = profileOf(dev.id)
            override suspend fun outgoing() = listOf(profileOf(dev.id))
            override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) = Unit
        }
        val effects = Effects(mutableListOf<String>().also { net.wipes[dev.id] = it })
        val apply = suspend { ChainApplier(dev.id, effects).apply(log.view()) }
        val replicator = ChainReplicator(log, transport, onChanged = apply, profiles = book)
        val source = ChainStateSource(log) { profileOf(dev.id) }
        val watch = ChainStateWatch(own = { source.current() }, pull = { replicator.reconcile(it) }, now = { net.clock }, jitterMs = { 0L })
        val tick = ChainTick(dev.id, log, replicator, transport, ChainBootstrap(log, { dev.signer }), { dev.signer }, onChanged = apply, watch = watch)
        return Node(dev, log, tick, source, watch).also { net.nodes[dev.id] = it }
    }

    /** What the health probes of one presence cycle tell every online device about every other online one. */
    private suspend fun probes(net: Net, online: Set<String>) {
        for (a in online) for (b in online) if (a != b) net.nodes.getValue(a).watch.note(b, net.nodes.getValue(b).source.current())
    }

    private suspend fun round(net: Net, online: Set<String>) {
        probes(net, online)
        net.clock += 60_000
        for (id in roster) if (id in online) net.nodes.getValue(id).tick.tick(roster, online - id, pins.getValue(id))
    }

    @Test
    fun aClusterWithNoChainStillEndsUpWithOneChain() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        val heads = net.nodes.values.map { it.log.view().state.head }.toSet()
        assertEquals(1, heads.size)
        assertEquals(roster.sorted(), net.nodes.getValue("mac").log.view().state.activeIds().sorted())
    }

    @Test
    fun equalChainsCostNoChainRequestAtAll() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.pulls = 0
        repeat(30) { round(net, roster) }
        assertEquals(0, net.pulls)
    }

    @Test
    fun aChangeReachesADeviceThatWasAwayAsSoonAsItsStateIsRead() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        val mac = net.nodes.getValue("mac")
        mac.tick.removeMember("amanda")
        net.offline -= "phone"
        val before = net.nodes.getValue("phone").log.view().state.seq
        probes(net, roster)
        assertTrue(net.nodes.getValue("phone").log.view().state.seq > before || before == mac.log.view().state.seq)
        assertEquals(mac.log.view().state.head, net.nodes.getValue("phone").log.view().state.head)
    }

    @Test
    fun aPeerWithNoStateIsStillPulledByTheTickSoNothingIsMissedFromOlderBuilds() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.pulls = 0
        val mac = net.nodes.getValue("mac")
        mac.tick.removeMember("amanda")
        net.clock += 13 * 60_000
        net.nodes.getValue("phone").tick.tick(roster, roster - "phone", pins.getValue("phone"))
        assertTrue("the tick pulled a peer it had no state for", net.pulls > 0)
        assertEquals(mac.log.view().state.head, net.nodes.getValue("phone").log.view().state.head)
    }

    // ---- the lifecycle matrix of rules section 4, with the state path on ----

    private fun logOf(net: Net, id: String) = net.nodes.getValue(id).log

    @Test
    fun aRemovalReachesEveryDeviceIncludingTheRemovedOneWhichWipes() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        assertTrue(net.nodes.getValue("amanda").tick.removeMember("phone") is ChainVerdict.Accepted)
        repeat(2) { round(net, roster) }
        for (id in roster) assertTrue("phone" !in logOf(net, id).view().state.activeIds())
        assertEquals(listOf("amanda"), net.wipes["phone"])
    }

    @Test
    fun aDeviceOfflineDuringARemovalLearnsItWhenItsStateIsReadAgain() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        assertTrue(net.nodes.getValue("amanda").tick.removeMember("phone") is ChainVerdict.Accepted)
        assertTrue("phone" in logOf(net, "phone").view().state.activeIds())
        net.offline -= "phone"
        probes(net, roster)
        assertTrue("phone" !in logOf(net, "phone").view().state.activeIds())
        assertEquals(listOf("amanda"), net.wipes["phone"])
    }

    @Test
    fun aRemovedDeviceWithNoPeerShowingStateStillPullsItsRosterAndSeesTheRemoval() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        assertTrue(net.nodes.getValue("amanda").tick.removeMember("phone") is ChainVerdict.Accepted)
        net.offline -= "phone"
        net.clock += 13 * 60_000
        net.nodes.getValue("phone").tick.tick(roster, emptySet(), pins.getValue("phone"))
        assertTrue("phone" !in logOf(net, "phone").view().state.activeIds())
        assertEquals(listOf("amanda"), net.wipes["phone"])
    }

    @Test
    fun aDeviceThatLeftIsLeftEverywhereAndCanBeAdmittedAgainWithANewKey() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        assertTrue(net.nodes.getValue("mac").tick.leave() is ChainVerdict.Accepted)
        repeat(2) { round(net, roster) }
        for (id in roster - "mac") assertTrue("mac" !in logOf(net, id).view().state.activeIds())
        val fresh = Dev("mac")
        node(net, fresh)
        val tick = net.nodes.getValue("mac").tick
        tick.notePairedInPerson()
        repeat(4) { round(net, roster) }
        for (id in roster) assertTrue("$id sees mac active", logOf(net, id).view().state.member("mac")?.active == true)
    }

    @Test
    fun aMemberWhoseTlsPinChangedIsFollowedByTheOthersThroughTheStatePath() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        val newPin = "ef".repeat(32)
        val changed = pins.toMutableMap().also { it["mac"] = newPin }
        probes(net, roster)
        net.clock += 60_000
        for (id in roster) net.nodes.getValue(id).tick.tick(roster, roster - id, changed.getValue(id))
        repeat(2) {
            probes(net, roster)
            net.clock += 60_000
            for (id in roster) net.nodes.getValue(id).tick.tick(roster, roster - id, changed.getValue(id))
        }
        assertEquals(newPin, logOf(net, "phone").view().state.member("mac")!!.tlsPin)
        assertEquals(1, logOf(net, "phone").view().canonical.count { it.kind == RecordKind.TLS_PIN })
    }

    @Test
    fun aDeviceThatWasAwayPullsTheExistingChainInsteadOfWritingAnotherGenesis() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        net.offline += "amanda"
        repeat(4) { round(net, roster - "amanda") }
        net.offline -= "amanda"
        repeat(4) { round(net, roster) }
        assertEquals(1, logOf(net, "amanda").view().canonical.count { it.kind == RecordKind.GENESIS })
        assertEquals(logOf(net, "mac").view().state.head, logOf(net, "amanda").view().state.head)
    }

    // ---- forks: the same chain on every device, whichever record each saw first ----

    @Test
    fun aRemovalAndAPinAnnouncementMadeAtTheSameMomentEndWithTheRemovalOnEveryDevice() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        val phone = devs.getValue("phone")
        val mac = devs.getValue("mac")
        logOf(net, "mac").append(mac.signer, RecordKind.REMOVE, "phone", phone.key, phone.pin, MembershipReason.USER_REMOVED)
        logOf(net, "phone").append(phone.signer, RecordKind.TLS_PIN, "phone", phone.key, "cd".repeat(32), MembershipReason.TLS_PIN)
        net.offline -= "phone"
        repeat(3) { round(net, roster) }
        val heads = roster.map { logOf(net, it).view().state.head }.toSet()
        assertEquals(1, heads.size)
        for (id in roster) assertTrue("phone" !in logOf(net, id).view().state.activeIds())
    }

    @Test
    fun twoRemovalsOfTheSameDeviceMadeAtTheSameMomentLeaveOneInTheChain() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        val phone = devs.getValue("phone")
        logOf(net, "mac").append(devs.getValue("mac").signer, RecordKind.REMOVE, "phone", phone.key, phone.pin, MembershipReason.USER_REMOVED)
        logOf(net, "amanda").append(devs.getValue("amanda").signer, RecordKind.REMOVE, "phone", phone.key, phone.pin, MembershipReason.USER_REMOVED)
        net.offline -= "phone"
        repeat(4) { round(net, roster) }
        assertEquals(1, roster.map { logOf(net, it).view().state.head }.toSet().size)
        assertEquals(1, logOf(net, "mac").view().canonical.count { it.kind == RecordKind.REMOVE })
    }

    @Test
    fun aLeaveAndARemovalOfTheSameDeviceMadeAtTheSameMomentEndWithOneChain() = runBlocking {
        val net = Net()
        devs.values.forEach { node(net, it) }
        repeat(6) { round(net, roster) }
        net.offline += "phone"
        val phone = devs.getValue("phone")
        logOf(net, "mac").append(devs.getValue("mac").signer, RecordKind.REMOVE, "phone", phone.key, phone.pin, MembershipReason.USER_REMOVED)
        logOf(net, "phone").append(phone.signer, RecordKind.LEAVE, "phone", phone.key, phone.pin, MembershipReason.LEFT_CLUSTER)
        net.offline -= "phone"
        repeat(4) { round(net, roster) }
        assertEquals(1, roster.map { logOf(net, it).view().state.head }.toSet().size)
        for (id in roster) assertTrue("phone" !in logOf(net, id).view().state.activeIds())
    }
}
