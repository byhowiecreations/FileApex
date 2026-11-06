package com.fileapex.di

import com.fileapex.data.db.FileApexDatabase
import com.fileapex.data.db.RoomChainStore
import com.fileapex.data.db.RoomPairingMarks
import com.fileapex.data.db.MemberStore
import com.fileapex.data.db.MembershipMetaEntity
import com.fileapex.data.db.RoomTlsPinStore
import com.fileapex.data.identity.clearPreviousDeviceId
import com.fileapex.data.device.DeviceRepository
import com.fileapex.domain.pairing.KickedByPeer
import com.fileapex.membership.ChainAckBook
import com.fileapex.membership.ChainApplier
import com.fileapex.membership.ChainEffects
import com.fileapex.membership.ChainBootstrap
import com.fileapex.membership.ChainReplicator
import com.fileapex.membership.ChainSigning
import com.fileapex.membership.ChainStateSource
import com.fileapex.membership.ChainStateWatch
import com.fileapex.membership.ChainTick
import com.fileapex.membership.TrustedPeer
import com.fileapex.membership.FallbackChainTransport
import com.fileapex.membership.MemberState
import com.fileapex.membership.IdentityChangeInbox
import com.fileapex.membership.IdentityChangeProof
import com.fileapex.membership.IdentityChangeRequester
import com.fileapex.network.HttpIdentityChangeLink
import com.fileapex.membership.MembershipChainLog
import com.fileapex.membership.MembershipHub
import com.fileapex.network.FileApexClient
import com.fileapex.network.HttpChainTransport
import com.fileapex.network.UnpinnedChainTransport
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Builds the membership layer of this device from the app's database and client, and runs its schedule. */
internal object MembershipWiring {
    fun create(database: FileApexDatabase, repository: DeviceRepository, client: FileApexClient): MembershipHub? {
        val signer = ChainSigning.provider() ?: return null
        val chainLog = MembershipChainLog(RoomChainStore(database.chainDao())) { signer.deviceId }
        val applier = ChainApplier(
            signer.deviceId,
            RoomChainEffects(
                RoomTlsPinStore(database.tlsPinDao()),
                database.membershipDao(),
                onPeerRemoved = ::tearDownRemoved,
                invalidateTombstones = { repository.invalidateTombstones() }
            )
        )
        val chainTransport = FallbackChainTransport(HttpChainTransport(repository, client), UnpinnedChainTransport(repository, client))
        val profiles = RoomChainProfiles(repository, signer.deviceId) { FileApexServices.pairingCoordinator.ownState() }
        val chainAcks = ChainAckBook(
            now = TimeUtils::now,
            descends = { change, theirHead -> chainLog.descendsOrEquals(theirHead, change) },
            report = { com.fileapex.platform.Diagnostics.log(it) }
        )
        val chainReplicator = ChainReplicator(
            chainLog, chainTransport, onChanged = { applier.apply(chainLog.view()) }, profiles = profiles,
            acks = chainAcks, scope = teardownScope, afterAnnounce = ::pokeLan,
            report = { com.fileapex.platform.Diagnostics.log(it) }
        )
        val chainSigner = { ChainSigning.provider() }
        val chainRoster: suspend () -> Set<String> = { repository.listDevices().filterNot { it.isRemoved }.map { it.deviceId }.toSet() + signer.deviceId }
        val trustedPeers: suspend () -> List<TrustedPeer> = {
            trustedPeersOf(database.membershipDao().allEffective(), repository.listDevices(), signer.deviceId)
        }
        val chainState = ChainStateSource(chainLog) { FileApexServices.pairingCoordinator.ownState() }
        val chainWatch = ChainStateWatch(
            own = { chainState.current() },
            pull = { chainReplicator.reconcile(it) },
            report = { com.fileapex.platform.Diagnostics.log(it) }
        )
        com.fileapex.membership.ChainStateInbox.watch = chainWatch
        val chainTick = ChainTick(
            signer.deviceId, chainLog, chainReplicator, chainTransport,
            ChainBootstrap(chainLog, chainSigner), chainSigner, trustedPeers,
            renewSigningKey = { ChainSigning.renew() },
            marks = RoomPairingMarks(database.membershipDao()),
            watch = chainWatch
        ) { applier.apply(chainLog.view()) }
        return MembershipHub(
            chainLog, chainReplicator, chainTick, chainRoster,
            replaceCluster = { keep ->
                repository.forgetClusterExcept(keep)
                repository.invalidateTombstones()
                com.fileapex.cloud.seal.ClusterRecordKeys.forget()
            },
            acknowledgeHistory = { applier.acknowledgeHistory(chainLog.view()) },
            markPairedInPerson = { id, seq -> applier.pairedInPerson(id, seq) },
            applyChain = { applier.apply(chainLog.view()) },
            chainState = chainState,
            chainWatch = chainWatch,
            continueUnderNewId = {
                val self = com.fileapex.data.identity.loadLocalIdentity()
                val own = ChainSigning.provider()
                val former = own?.let { IdentityChangeProof.formerId(chainLog.view().state, it.deviceId, it.publicKey) } ?: self.previousDeviceId.takeIf { it.isNotBlank() }
                if (own != null && former != null && former != own.deviceId) {
                    IdentityChangeRequester(
                        signer = own, previousId = former, newId = own.deviceId,
                        newE2eeKey = com.fileapex.domain.clipboard.ClipboardE2ee.publicKeyBase64(),
                        hardwareHash = com.fileapex.platform.HardwareId.local,
                        peers = { repository.listDevices().filterNot { it.isRemoved || it.deviceId == own.deviceId || it.deviceId == former }.map { it.deviceId } },
                        link = HttpIdentityChangeLink(repository, client),
                        nonce = { java.util.UUID.randomUUID().toString() },
                        pause = { delay(IDENTITY_CHANGE_POLL_MS) },
                        maxPolls = (IDENTITY_CHANGE_TTL_MS / IDENTITY_CHANGE_POLL_MS).toInt()
                    ).run { com.fileapex.security.IdentityChangeNotice.requesting(it) }
                }
            },
            identityChanges = IdentityChangeInbox(
                member = { chainLog.view().state.member(it) },
                knownHardware = { repository.getDevice(it)?.hardwareHash.orEmpty() },
                oldIdAnswers = { id -> repository.getDevice(id)?.let { com.fileapex.domain.pairing.HardwareTwins.answersAs(it) } == true },
                confirm = { chainTick.confirmIdentityChange(it) },
                now = com.fileapex.util.TimeUtils::now,
                ttlMs = IDENTITY_CHANGE_TTL_MS
            ),
            prepare = {
                database.membershipDao().putMeta(MembershipMetaEntity(MemberStore.SELF_ID_KEY, signer.deviceId))
                val pruned = chainLog.pruneOtherRoots()
                if (pruned > 0) println("MembershipWiring: $pruned records of another root were removed")
                database.membershipDao().refreshMembers()
            }
        )
    }

    /** A device the chain removed: its session ends, presence is recounted and the cloud link learns of it. */
    private fun tearDownRemoved(deviceId: String) {
        com.fileapex.session.DeviceSessionManager.clearSession(deviceId)
        teardownScope.launch {
            cancellableCatching {
                FileApexServices.presenceMonitor.refreshOnlineSnapshot()
                com.fileapex.cloud.GoogleLinkCoordinator.publishRemovedPeer(deviceId)
            }.onFailure { println("MembershipWiring: teardown of a removed device failed - ${it::class.simpleName}") }
        }
    }

    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var lastPokeAt = 0L

    /** After a change the signed hello goes out on the LAN, so devices that hear it look at this device's state at once. At most once every few seconds. */
    private fun pokeLan() {
        val at = TimeUtils.now()
        if (at - lastPokeAt < POKE_GAP_MS) return
        lastPokeAt = at
        teardownScope.launch { cancellableCatching { com.fileapex.network.sendWakeBroadcast() } }
    }

    /** Waits for the signing identity, then catches up with a peer when it comes online and every minute runs the chain tick. */
    fun start(scope: CoroutineScope, hub: () -> MembershipHub?, onlineIds: StateFlow<Set<String>>) {
        scope.launch {
            var ready: MembershipHub? = null
            repeat(WAIT_ATTEMPTS) {
                ready = hub()
                if (ready != null) return@repeat
                delay(WAIT_MS)
            }
            val running = ready ?: return@launch
            cancellableCatching { running.prepare() }.onFailure { println("MembershipWiring: preparing the roster failed - ${it::class.simpleName}") }
            scope.launch {
                var known = emptySet<String>()
                onlineIds.collect { ids ->
                    (ids - known).forEach { cancellableCatching { running.chainReplicator.reconcile(it) } }
                    known = ids
                }
            }
            var ticks = 0
            while (true) {
                cancellableCatching {
                    running.applyChain()
                    running.chainTick.tick(running.chainRoster(), onlineIds.value, com.fileapex.security.tls.LocalTlsInfo.pin)
                    val self = com.fileapex.data.identity.loadLocalIdentity()
                    val settled = com.fileapex.security.IdentityChangeNotice.settled(
                        running.chainLog.view().state.member(self.deviceId)?.active == true,
                        self.previousDeviceId,
                        ::clearPreviousDeviceId
                    )
                    val due = com.fileapex.util.TimeUtils.now() - lastIdentityAsk >= IDENTITY_CHANGE_RETRY_MS
                    val ownSigner = ChainSigning.provider()
                    val former = ownSigner?.let { com.fileapex.membership.IdentityChangeProof.formerId(running.chainLog.view().state, it.deviceId, it.publicKey) }
                    if (former != null && former != reportedFormerId) {
                        reportedFormerId = former
                        com.fileapex.security.IdentityChangeNotice.report(former, self.deviceId)
                    }
                    if (!settled && (self.previousDeviceId.isNotBlank() || former != null) && due) {
                        lastIdentityAsk = com.fileapex.util.TimeUtils.now()
                        scope.launch { cancellableCatching { running.continueUnderNewId() } }
                    }
                }.onFailure { println("MembershipWiring: tick failed - ${it::class.simpleName}") }
                if (++ticks % SUMMARY_EVERY_TICKS == 0) running.chainWatch?.let { com.fileapex.platform.Diagnostics.log(it.summary()) }
                delay(TICK_MS)
            }
        }
    }

    @Volatile
    private var lastIdentityAsk = 0L

    @Volatile
    private var reportedFormerId = ""

    private const val WAIT_ATTEMPTS = 30
    private const val WAIT_MS = 10_000L
    private const val IDENTITY_CHANGE_TTL_MS = 10L * 60_000L
    private const val IDENTITY_CHANGE_POLL_MS = 2_000L
    private const val IDENTITY_CHANGE_RETRY_MS = 10L * 60_000L
    private const val TICK_MS = 60_000L
    private const val POKE_GAP_MS = 10_000L
    private const val SUMMARY_EVERY_TICKS = 60
}

/** The chain's effects on this device: its pins go to the pin store, its removal goes to the one self-wipe path. */
internal class RoomChainEffects(
    private val pins: RoomTlsPinStore,
    private val dao: com.fileapex.data.db.MembershipDao,
    private val onSelfRemoved: (by: String) -> Unit = { by -> KickedByPeer.onUserRemoved(by, TimeUtils.now()) },
    /** Everything running for a device the chain removed: its session, its presence and its cloud link. */
    private val onPeerRemoved: (deviceId: String) -> Unit = {},
    private val invalidateTombstones: () -> Unit = {},
    private val now: () -> Long = TimeUtils::now
) : ChainEffects {
    override suspend fun knownPin(deviceId: String): String? = pins.entries().firstOrNull { it.deviceId == deviceId && !it.revoked }?.pin

    override suspend fun setPin(deviceId: String, pin: String) {
        val current = pins.entries().firstOrNull { it.deviceId == deviceId && !it.revoked }
        val alt = when {
            current == null -> ""
            current.pin == pin -> current.alt
            else -> current.pin
        }
        pins.record(deviceId, pin, 0, alt = alt, rejoin = true, userConfirmed = true)
        invalidateTombstones()
    }

    override suspend fun ensureProfile(deviceId: String) =
        dao.putProfileIfAbsent(com.fileapex.data.db.MemberProfileEntity.stub(deviceId, com.fileapex.data.device.DeviceDisplayNames.FALLBACK))

    override suspend fun selfRemoved(by: String, recordHash: String) = onSelfRemoved(by)

    override suspend fun knownRemoved(deviceId: String): Boolean = dao.get(deviceId)?.let { it.state != MemberState.ACTIVE.name } == true

    override suspend fun refreshRoster() = dao.refreshMembers()

    override suspend fun holdRemoval(deviceId: String, chainSeq: Long) = dao.holdRemoval(deviceId, now(), chainSeq)

    override suspend fun removalHeld(deviceId: String, removalSeq: Long): Boolean =
        com.fileapex.membership.MemberProjection.held(dao.localFacts(deviceId), removalSeq, now())

    override suspend fun markRemoved(deviceId: String, by: String, reason: String, tlsPin: String) {
        dao.retainRevokedPin(deviceId, tlsPin)
        invalidateTombstones()
        onPeerRemoved(deviceId)
    }

    override suspend fun handledRemoval(): String = dao.meta(HANDLED_KEY).orEmpty()

    override suspend fun markHandled(recordHash: String) = dao.putMeta(MembershipMetaEntity(HANDLED_KEY, recordHash))

    private companion object {
        const val HANDLED_KEY = "chain_handled_removal"
    }
}
