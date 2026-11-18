package com.fileapex.network

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceRepository
import com.fileapex.data.device.InMemoryDeviceDao
import com.fileapex.data.device.LocalDeviceRef
import com.fileapex.data.device.TlsPinEntry
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerNodeState
import com.fileapex.membership.ChainAnswer
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainTick
import com.fileapex.membership.ChainTransport
import com.fileapex.membership.ChainRecord
import com.fileapex.membership.ChainVerdict
import com.fileapex.membership.EcEventSigner
import com.fileapex.membership.IdentityChangeInbox
import com.fileapex.membership.IdentityChangeRequester
import com.fileapex.membership.InMemoryChainStore
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipHub
import com.fileapex.membership.MembershipKeys
import com.fileapex.membership.RecordKind
import com.fileapex.security.IdentityBinding
import com.fileapex.security.tls.PeerTlsConnector
import com.fileapex.security.tls.PeerTlsRoutes
import com.fileapex.security.tls.RosterTlsPeerDirectory
import com.fileapex.security.tls.TlsFrontServer
import com.fileapex.security.tls.TlsIdentityFactory
import com.fileapex.security.tls.TlsIdentityStore
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A device whose id changed asks a member to continue under the new one, over a real pinned TLS connection and the
 * real route, with the member's real inbox and chain behind it. The unit tests cover each part; this covers the hop.
 */
class IdentityChangeOverHttpTest {
    @get:Rule
    val temp = TemporaryFolder()

    private class Dev(val id: String) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val signer = EcEventSigner(id, pair.private, pair.public)
    }

    private val macId = "mac"
    private val oldId = "fx-old-phone"
    private val mac = Dev(macId)
    private val phone = Dev(oldId)
    private val newE2ee = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded)
    private val newId = IdentityBinding.deriveDeviceId(newE2ee)
    private val serverIdentity = TlsIdentityFactory.generate()
    private val directory = RosterTlsPeerDirectory()
    private lateinit var phoneTlsStore: TlsIdentityStore
    private lateinit var log: MembershipChainLog
    private lateinit var inbox: IdentityChangeInbox
    private lateinit var phoneRepo: DeviceRepository
    private lateinit var macRepo: DeviceRepository
    private var oldIdStillAnswers = false
    private var memberKnowsPhone = true
    private val logged = java.util.Collections.synchronizedList(mutableListOf<String>())
    private var server: FileApexServer? = null
    private var front: TlsFrontServer? = null
    private var httpPort = 0
    private var tlsPort = 0

    /** The peer policy refuses loopback, so the hop runs over this machine's own private LAN address. */
    private val lanIp: String? = java.net.NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<java.net.Inet4Address>()
        .map { it.hostAddress }
        .firstOrNull { com.fileapex.util.NetworkUtils.isPrivateLanPeerHost(it) }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Before
    fun start() {
        Assume.assumeTrue("no private LAN address on this machine", lanIp != null)
        phoneTlsStore = TlsIdentityStore(File(temp.root, "phone"), emptyList())
        val phoneTls = phoneTlsStore.getOrCreate()
        PeerTlsConnector.configure { phoneTlsStore }
        log = MembershipChainLog(InMemoryChainStore())
        val transport = object : ChainTransport {
            override suspend fun push(peerId: String, records: List<ChainRecord>, profiles: List<PeerNodeState>): ChainAnswer? = null
            override suspend fun pull(peerId: String): ChainAnswer? = null
            override suspend fun join(peerId: String, key: String, tlsPin: String, profile: PeerNodeState?): ChainAnswer? = null
        }
        runBlocking {
            log.append(mac.signer, RecordKind.GENESIS, macId, mac.signer.publicKey, MembershipKeys.pin(mac.signer.publicKey), "paired")
            log.append(mac.signer, RecordKind.ADMIT, oldId, phone.signer.publicKey, phoneTls.pin, "paired")
        }
        val replicator = ChainReplicator(log, transport)
        val tick = ChainTick(macId, log, replicator, transport, ChainBootstrap(log) { mac.signer }, { mac.signer })
        inbox = IdentityChangeInbox(
            member = { log.view().state.member(it) },
            knownHardware = { "hw" },
            oldIdAnswers = { oldIdStillAnswers },
            confirm = { tick.confirmIdentityChange(it) },
            now = System::currentTimeMillis,
            ttlMs = 60_000L
        )
        macRepo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef(macId, emptySet()) })
        runBlocking {
            macRepo.adoptFromPairing(
                PairedDeviceEntity(
                    deviceId = oldId, deviceName = "Honor", lastKnownIp = "10.0.0.50", port = 8080, publicKeyHash = "hash-honor",
                    publicKey = "key-honor", rootPath = "/", tlsPin = phoneTls.pin, tlsPort = 8081, clusterVersion = 1L
                ),
                1L
            )
        }
        FileApexServices.installMembershipForTest(macRepo, MembershipHub(log, replicator, tick, { setOf(macId) }, identityChanges = inbox) {})
        directory.refresh(listOf(TlsPinEntry(oldId, phoneTls.pin, alt = "", port = 8081, revoked = false, signed = true)))
        httpPort = freePort()
        val bridgePort = freePort()
        server = FileApexServer(
            port = httpPort,
            bridgePort = bridgePort,
            identityProvider = { LocalIdentity(deviceId = macId, deviceName = "Mac", rootPath = temp.root.absolutePath, sharePort = httpPort) },
            isRosterMember = { memberKnowsPhone && macRepo.hasActiveDevice(it) }
        ).also { it.start() }
        waitUntilListening(httpPort)
        front = TlsFrontServer({ serverIdentity }, directory, lanIp!!, 0, bridgePort) { _, _ -> }
        tlsPort = front!!.start()
        val macEntry = PairedDeviceEntity(
            deviceId = macId, deviceName = "Mac", lastKnownIp = lanIp!!, port = httpPort,
            publicKeyHash = "", rootPath = "/", tlsPin = serverIdentity.pin, tlsPort = tlsPort
        )
        PeerTlsRoutes.update(listOf(macEntry))
        phoneRepo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef(oldId, emptySet()) })
        runBlocking { phoneRepo.adoptFromPairing(macEntry.copy(publicKeyHash = "hash-mac", publicKey = "key-mac", clusterVersion = 1L), 1L) }
        Thread.sleep(300)
    }

    @After
    fun stop() {
        front?.stop()
        server?.stop()
        PeerTlsRoutes.update(emptyList())
        FileApexServices.installMembershipForTest(null, null)
    }

    private fun requester(pollMs: Long = 50L, maxPolls: Int = 100) = IdentityChangeRequester(
        signer = phone.signer, previousId = oldId, newId = newId, newE2eeKey = newE2ee, hardwareHash = "hw",
        peers = { listOf(macId) },
        link = HttpIdentityChangeLink(phoneRepo, FileApexClient(localDeviceId = { oldId }), log = { logged += it }),
        nonce = { "nonce-1" },
        pause = { delay(pollMs) },
        maxPolls = maxPolls
    )

    /** Plays the member's user: waits for the one prompt, returns the code it carries, then answers. */
    private suspend fun memberUser(accept: Boolean): String {
        withTimeout(10_000L) { while (inbox.pending.value.isEmpty()) delay(20) }
        val shown = inbox.pending.value.single()
        if (accept) assertTrue(inbox.accept(shown.request.oldId) is ChainVerdict.Accepted) else inbox.decline(shown.request.oldId)
        return shown.code
    }

    @Test
    fun theMemberSeesOneCodeTheChangedDeviceShowsAndConfirmingContinuesTheDeviceUnderTheNewId() = runBlocking {
        val req = requester()
        var asked: IdentityChangeRequester.State.Asking? = null
        val run = launch { req.run { if (it is IdentityChangeRequester.State.Asking) asked = it } }
        val memberCode = memberUser(accept = true)
        run.join()
        assertEquals(IdentityChangeRequester.State.Done(macId), req.state.value)
        assertEquals(macId, asked!!.peerId)
        assertEquals(memberCode, asked!!.code)
        val state = log.view().state
        assertFalse(state.member(oldId)!!.active)
        assertTrue(state.member(newId)!!.active)
        assertEquals(phone.signer.publicKey, state.member(newId)!!.key)
        assertEquals(state.member(oldId)!!.tlsPin, state.member(newId)!!.tlsPin)
    }

    @Test
    fun decliningOnTheMemberWritesNothingAndTheChangedDeviceHearsIt() = runBlocking {
        val before = log.view().state.seq
        val req = requester()
        val run = launch { req.run() }
        memberUser(accept = false)
        run.join()
        assertEquals(IdentityChangeRequester.State.Failed(IdentityChangeRequester.Failure.DECLINED), req.state.value)
        assertEquals(before, log.view().state.seq)
    }

    @Test
    fun anOldIdThatStillAnswersIsRefusedBeforeAnyPrompt() = runBlocking {
        oldIdStillAnswers = true
        val before = log.view().state.seq
        val req = requester()
        req.run()
        assertEquals(IdentityChangeRequester.State.Failed(IdentityChangeRequester.Failure.NOBODY_ANSWERED), req.state.value)
        assertTrue(inbox.pending.value.isEmpty())
        assertEquals(before, log.view().state.seq)
        assertTrue("a refusal is an answer, not a failure: $logged", logged.isEmpty())
    }

    @Test
    fun aConnectionUnderADifferentTlsKeyNeverReachesTheInbox() = runBlocking {
        PeerTlsConnector.configure { TlsIdentityStore(File(temp.root, "thief"), emptyList()) }
        val before = log.view().state.seq
        val req = requester()
        req.run()
        assertEquals(IdentityChangeRequester.State.Failed(IdentityChangeRequester.Failure.NOBODY_ANSWERED), req.state.value)
        assertTrue(inbox.pending.value.isEmpty())
        assertEquals(before, log.view().state.seq)
        assertTrue("an unreachable member must be logged: $logged", logged.any { it.contains(macId) })
    }

    @Test
    fun aMemberThatAnswers401IsLoggedWithTheStatusAndNotSwallowed() = runBlocking {
        memberKnowsPhone = false
        val req = requester()
        req.run()
        assertEquals(IdentityChangeRequester.State.Failed(IdentityChangeRequester.Failure.NOBODY_ANSWERED), req.state.value)
        assertTrue("the 401 must be logged: $logged", logged.any { it.contains(macId) && it.contains("401") })
    }

    @Test
    fun aHostThePeerPolicyRefusesIsLoggedNotSwallowed() = runBlocking {
        val repo = DeviceRepository(InMemoryDeviceDao(), { LocalDeviceRef(oldId, emptySet()) })
        assertTrue(
            repo.adoptFromPairing(
                PairedDeviceEntity(deviceId = macId, deviceName = "Mac", lastKnownIp = "192.0.2.1", port = httpPort, publicKeyHash = "h", publicKey = "k", rootPath = "/", clusterVersion = 1L),
                1L
            )
        )
        assertTrue(repo.getDevice(macId) != null)
        val answer = HttpIdentityChangeLink(repo, FileApexClient(localDeviceId = { oldId }), log = { logged += it })
            .send(macId, com.fileapex.membership.IdentityChangeProof.request(phone.signer, oldId, newId, newE2ee, "hw", "n"))
        assertEquals(null, answer)
        assertTrue("the refused host must be logged: $logged", logged.any { it.contains(macId) })
    }

    private fun waitUntilListening(port: Int) {
        repeat(50) {
            if (runCatching { ServerSocket(port).use { } }.isFailure) return
            Thread.sleep(100)
        }
    }
}
