package com.fileapex.membership

import com.fileapex.domain.peer.PeerNodeState
import java.security.KeyPairGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainTickTest {
    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
        val key get() = signer.publicKey
        val pin get() = MembershipKeys.pin(key)
    }

    private val devs = listOf(Dev("amanda"), Dev("mac"), Dev("phone")).associateBy { it.id }
    private val roster = devs.keys
    private val pins = devs.mapValues { it.value.pin }.toMutableMap()

    /** What one device knows about the others: its own state, and whatever it was told about active members. */
    private class Book(val self: String) : ChainProfiles {
        val known = mutableMapOf<String, PeerNodeState>()
        override suspend fun own(): PeerNodeState = PeerNodeState(
            deviceId = self, deviceName = "name-$self", ipAddress = "10.0.0.${self.length}",
            port = 8080 + self.length, publicKey = "e2ee-$self", tlsPort = 8443
        )
        override suspend fun outgoing(): List<PeerNodeState> = listOf(own()) + known.values
        override suspend fun incoming(states: List<PeerNodeState>, activeIds: Set<String>, from: String) {
            states.filter { it.deviceId != self && it.deviceId in activeIds }.forEach { known[it.deviceId] = it }
        }
    }

    private class Net {
        val books = mutableMapOf<String, Book>()
        val ticks = mutableMapOf<String, ChainTick>()
        val offline = mutableSetOf<String>()
        val wipes = mutableMapOf<String, MutableList<String>>()
        fun transportFor(self: String) = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<com.fileapex.domain.peer.PeerNodeState>): ChainAnswer? =
                if (peerId in offline || peerId !in ticks) null else ticks.getValue(peerId).replicator.receive(self, records, profiles)

            override suspend fun pull(peerId: String): ChainAnswer? =
                if (peerId in offline || peerId !in ticks) null else ticks.getValue(peerId).replicator.serve()

            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? =
                if (peerId in offline || peerId !in ticks) null else ticks.getValue(peerId).receiveJoin(self, key, tlsPin, ticks.keys, profile)
        }
    }

    private class Effects(private val wipes: MutableList<String>) : ChainEffects {
        override suspend fun refreshRoster() = Unit
        override suspend fun holdRemoval(deviceId: String, chainSeq: Long) = Unit
        override suspend fun removalHeld(deviceId: String, removalSeq: Long) = false
        private var handled = ""
        override suspend fun knownPin(deviceId: String): String? = null
        override suspend fun setPin(deviceId: String, pin: String) {}
        override suspend fun ensureProfile(deviceId: String) {}
        override suspend fun selfRemoved(by: String, recordHash: String) { wipes += by }
        override suspend fun handledRemoval(): String = handled
        override suspend fun markHandled(recordHash: String) { handled = recordHash }
        override suspend fun knownRemoved(deviceId: String): Boolean = false
        override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) {}
    }

    private fun node(net: Net, dev: Dev, trusted: suspend () -> List<TrustedPeer> = { emptyList() }): MembershipChainLog {
        val log = MembershipChainLog(InMemoryChainStore())
        val transport = net.transportFor(dev.id)
        val book = Book(dev.id).also { net.books[dev.id] = it }
        val wipes = mutableListOf<String>().also { net.wipes[dev.id] = it }
        val effects = Effects(wipes)
        val apply = suspend { ChainApplier(dev.id, effects).apply(log.view()) }
        val replicator = ChainReplicator(log, transport, onChanged = apply, profiles = book)
        net.ticks[dev.id] = ChainTick(dev.id, log, replicator, transport, ChainBootstrap(log, { dev.signer }), { dev.signer }, trustedPeers = trusted, onChanged = apply)
        return log
    }

    private suspend fun round(net: Net, online: Set<String>) {
        for (id in roster) if (id in online) net.ticks.getValue(id).tick(roster, online - id, pins.getValue(id))
    }

    @Test
    fun aClusterWithNoChainEndsUpWithOneChainOfAllItsDevices() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        val heads = logs.values.map { it.view().state.head }.toSet()
        assertEquals(1, heads.size)
        assertEquals(roster.sorted(), logs.getValue("mac").view().state.activeIds().sorted())
    }

    @Test
    fun aDeviceThatWasOfflineJoinsWhenItIsBackAndOnlyWhenItWasNeverInTheChain() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.offline += "phone"
        repeat(3) { round(net, roster - "phone") }
        assertEquals(listOf("amanda", "mac"), logs.getValue("amanda").view().state.activeIds().sorted())
        net.offline -= "phone"
        repeat(3) { round(net, roster) }
        assertEquals(roster.sorted(), logs.getValue("phone").view().state.activeIds().sorted())
    }

    @Test
    fun aRemovedDeviceDoesNotJoinAgainOnItsOwn() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        val phone = devs.getValue("phone")
        logs.getValue("amanda").append(devs.getValue("amanda").signer, RecordKind.REMOVE, "phone", phone.key, phone.pin, MembershipReason.USER_REMOVED)
        repeat(4) { round(net, roster) }
        assertTrue("phone" !in logs.getValue("mac").view().state.activeIds())
    }

    @Test
    fun aMemberWhoseTlsPinChangedAnnouncesItAndTheOthersFollow() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        val newPin = "ef".repeat(32)
        pins["mac"] = newPin
        repeat(2) { round(net, roster) }
        assertEquals(newPin, logs.getValue("phone").view().state.member("mac")!!.tlsPin)
        assertEquals(1, logs.getValue("phone").view().canonical.count { it.kind == RecordKind.TLS_PIN })
    }

    @Test
    fun anUnchangedPinWritesNoRecord() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(6) { round(net, roster) }
        assertEquals(0, logs.getValue("mac").view().canonical.count { it.kind == RecordKind.TLS_PIN })
    }

    @Test
    fun removingAMemberWritesASignedRecordThatReachesEveryDeviceIncludingTheRemovedOne() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        assertTrue(net.ticks.getValue("amanda").removeMember("phone") is ChainVerdict.Accepted)
        assertTrue("phone" !in logs.getValue("phone").view().state.activeIds())
        assertTrue("phone" !in logs.getValue("mac").view().state.activeIds())
        val record = logs.getValue("mac").view().canonical.last()
        assertEquals(RecordKind.REMOVE, record.kind)
        assertEquals(MembershipReason.USER_REMOVED, record.reason)
        assertEquals("amanda", record.actor)
    }

    @Test
    fun aDeviceThatIsNotInTheChainCannotBeRemovedThroughIt() = runBlocking {
        val net = Net()
        node(net, devs.getValue("amanda"))
        node(net, devs.getValue("mac"))
        round(net, setOf("amanda"))
        assertTrue(net.ticks.getValue("amanda").removeMember("mac") is ChainVerdict.Rejected)
    }

    @Test
    fun leavingWritesALeaveRecordForThisDevice() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        assertTrue(net.ticks.getValue("mac").leave() is ChainVerdict.Accepted)
        assertTrue("mac" !in logs.getValue("amanda").view().state.activeIds())
        assertEquals(RecordKind.LEAVE, logs.getValue("amanda").view().canonical.last().kind)
    }

    @Test
    fun theGenesisIsWrittenByTheLowestIdAmongTheDevicesThatCanAnswerForTheChain() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.offline += "amanda"
        repeat(3) { round(net, roster - "amanda") }
        assertEquals(listOf("mac", "phone"), logs.getValue("mac").view().state.activeIds().sorted())
        assertEquals("mac", logs.getValue("mac").view().canonical.first().actor)
        assertEquals(0L, logs.getValue("amanda").view().state.seq)
    }

    @Test
    fun aDeviceThatComesBackPullsTheExistingChainInsteadOfWritingAnotherGenesis() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.offline += "amanda"
        repeat(3) { round(net, roster - "amanda") }
        net.offline -= "amanda"
        repeat(3) { round(net, roster) }
        assertEquals(1, logs.getValue("amanda").view().canonical.count { it.kind == RecordKind.GENESIS })
        assertEquals(logs.getValue("mac").view().state.head, logs.getValue("amanda").view().state.head)
        assertEquals(roster.sorted(), logs.getValue("mac").view().state.activeIds().sorted())
    }

    @Test
    fun aRemovedDeviceWithNoPeerOnlineStillPullsTheChainFromItsRosterAndSeesItsRemoval() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        net.offline += "phone"
        assertTrue(net.ticks.getValue("amanda").removeMember("phone") is ChainVerdict.Accepted)
        assertTrue("phone" in logs.getValue("phone").view().state.activeIds())
        net.offline -= "phone"
        net.ticks.getValue("phone").tick(roster, emptySet(), pins.getValue("phone"))
        assertTrue("phone" !in logs.getValue("phone").view().state.activeIds())
        assertEquals(listOf("amanda"), net.wipes["phone"])
    }

    @Test
    fun aPeerWhoseTlsKeyChangedWhileThisDeviceWasOfflineIsStillPulledFromTheRoster() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        net.offline += "phone"
        val newPin = "ab".repeat(32)
        pins["mac"] = newPin
        round(net, roster - "phone")
        net.offline -= "phone"
        net.ticks.getValue("phone").tick(roster, emptySet(), pins.getValue("phone"))
        assertEquals(newPin, logs.getValue("phone").view().state.member("mac")!!.tlsPin)
    }

    @Test
    fun aDeviceThatWasRemovedAndPairedAgainWithANewKeyIsAdmittedAndEveryoneFollows() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }.toMutableMap()
        repeat(4) { round(net, roster) }
        assertTrue(net.ticks.getValue("amanda").removeMember("phone") is ChainVerdict.Accepted)
        val fresh = Dev("phone")
        pins["phone"] = fresh.pin
        logs["phone"] = node(net, fresh)
        repeat(4) { round(net, roster) }
        for (id in roster) {
            val phone = logs.getValue(id).view().state.member("phone")!!
            assertTrue("$id sees phone active", phone.active)
            assertEquals(fresh.key, phone.key)
        }
    }

    @Test
    fun aRemovedDeviceThatKeptItsKeyDoesNotAskToJoinEvenWhenPeersAreListed() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        assertTrue(net.ticks.getValue("amanda").removeMember("phone") is ChainVerdict.Accepted)
        repeat(4) { round(net, roster) }
        assertEquals(1, logs.getValue("mac").view().canonical.count { it.kind == RecordKind.REMOVE })
        assertTrue("phone" !in logs.getValue("mac").view().state.activeIds())
    }

    @Test
    fun admittingADeviceIsAppliedAndAnnouncedToTheOtherMembersAtOnce() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.offline += "phone"
        repeat(3) { round(net, roster - "phone") }
        val phone = devs.getValue("phone")
        net.ticks.getValue("amanda").receiveJoin("phone", phone.key, phone.pin, roster)
        assertTrue("phone" in logs.getValue("mac").view().state.activeIds())
    }

    @Test
    fun aDeviceWithNoPeerOnItsRosterWritesNoGenesisOfItsOwn() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.ticks.getValue("amanda").tick(setOf("amanda"), emptySet(), pins.getValue("amanda"))
        assertEquals(0L, logs.getValue("amanda").view().state.seq)
    }

    @Test
    fun aJoinerLearnsRosterPeersTheHostAlreadyTrustedEvenWhenThosePeersCannotAnswerTheChain() = runBlocking {
        val honor = Dev("honor")
        val mac = devs.getValue("mac")
        val phone = devs.getValue("phone")
        val net = Net()
        val macLog = node(net, mac) {
            listOf(TrustedPeer(honor.id, honor.key, honor.pin))
        }
        net.books.getValue("mac").known[honor.id] = PeerNodeState(
            deviceId = honor.id, deviceName = "name-honor", ipAddress = "10.0.0.5",
            port = 8085, publicKey = "e2ee-honor", tlsPort = 8443
        )
        val phoneLog = node(net, phone)
        net.ticks.getValue("mac").tick(setOf("mac", "honor"), emptySet(), mac.pin)
        net.ticks.getValue("phone").tick(setOf("phone", "mac"), setOf("mac"), phone.pin)
        assertTrue("honor" in macLog.view().state.activeIds())
        assertTrue("honor" in phoneLog.view().state.activeIds())
        assertEquals("name-honor", net.books.getValue("phone").known.getValue("honor").deviceName)
        assertEquals(honor.pin, phoneLog.view().state.member("honor")!!.tlsPin)
    }

    @Test
    fun aKeylessMemberThatNowHasAKeyAsksToBeBoundAndEveryoneFollows() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        val old = Dev("old")
        val mac = devs.getValue("mac")
        logs.getValue("mac").append(mac.signer, RecordKind.ADMIT, "old", "legacy:old", old.pin, "paired")
        val oldLog = node(net, old)
        net.ticks.getValue("old").tick(roster.toSet() + "old", setOf("mac"), old.pin)
        assertEquals(old.key, logs.getValue("mac").view().state.member("old")!!.key)
        assertEquals(old.key, oldLog.view().state.member("old")!!.key)
        assertTrue(logs.getValue("mac").view().state.member("old")!!.active)
    }

    @Test
    fun aMemberThatAlreadyHasARealKeyWritesNothingMore() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        repeat(4) { round(net, roster) }
        val before = logs.getValue("mac").view().canonical.size
        repeat(3) { round(net, roster) }
        assertEquals(before, logs.getValue("mac").view().canonical.size)
    }

    @Test
    fun aPeerThatSpeaksTheChainIsNeverAdmittedWithoutItsOwnKey() = runBlocking {
        val newPhone = Dev("newphone")
        val mac = devs.getValue("mac")
        val net = Net()
        val macLog = node(net, mac) { listOf(TrustedPeer(newPhone.id, "", newPhone.pin, mayBeLegacy = false)) }
        net.ticks.getValue("mac").tick(setOf("mac", "newphone"), emptySet(), mac.pin)
        assertTrue("newphone" !in macLog.view().state.activeIds())
    }

    @Test
    fun aPeerThatIsKnownToLackTheChainIsAdmittedAsLegacy() = runBlocking {
        val old = Dev("old")
        val mac = devs.getValue("mac")
        val net = Net()
        val macLog = node(net, mac) { listOf(TrustedPeer(old.id, "", old.pin, mayBeLegacy = true)) }
        net.ticks.getValue("mac").tick(setOf("mac", "old"), emptySet(), mac.pin)
        assertEquals("legacy:old", macLog.view().state.member("old")!!.key)
    }

    @Test
    fun aDeviceThatPairedWithOneMemberLearnsEveryoneAndIsLearnedByEveryoneOnTheSameRoundTrip() = runBlocking {
        val net = Net()
        val logs = devs.values.associate { it.id to node(net, it) }
        net.offline += "phone"
        repeat(3) { round(net, roster - "phone") }
        net.offline -= "phone"
        net.ticks.getValue("phone").tick(setOf("phone", "mac"), setOf("mac"), pins.getValue("phone"))
        assertEquals(setOf("amanda", "mac"), net.books.getValue("phone").known.keys)
        val phoneOnAmanda = net.books.getValue("amanda").known.getValue("phone")
        val amandaOnPhone = net.books.getValue("phone").known.getValue("amanda")
        assertEquals(listOf("name-phone", "10.0.0.5", 8085, "e2ee-phone", 8443), listOf(phoneOnAmanda.deviceName, phoneOnAmanda.ipAddress, phoneOnAmanda.port, phoneOnAmanda.publicKey, phoneOnAmanda.tlsPort))
        assertEquals(listOf("name-amanda", "10.0.0.6", 8086, "e2ee-amanda", 8443), listOf(amandaOnPhone.deviceName, amandaOnPhone.ipAddress, amandaOnPhone.port, amandaOnPhone.publicKey, amandaOnPhone.tlsPort))
        assertEquals(pins.getValue("phone"), logs.getValue("amanda").view().state.member("phone")!!.tlsPin)
        assertEquals(pins.getValue("amanda"), logs.getValue("phone").view().state.member("amanda")!!.tlsPin)
    }
}
