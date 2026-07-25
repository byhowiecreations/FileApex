package com.fileapex.cloud

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.pairing.RemovedDeviceRecord
import com.fileapex.util.DeviceIdentityMarkers
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimestampDiagnostics
import com.fileapex.update.currentAppVersionCode
import com.fileapex.update.currentAppVersionName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in Google Account linking + Firestore virtual device registry.
 * Single source of truth for cloud pairing seed → local [com.fileapex.data.device.DeviceRepository].
 *
 * [deviceName] is written to Firestore only from explicit user rename actions
 * ([publishUserRenamedDevice]). Presence fields publish on cold launch, link/restore, rename,
 * and every [LanPresenceTiming.FIRESTORE_PRESENCE_HEARTBEAT_MS] while the share server runs.
 *
 * Session teardown always drains Firestore listeners and session coroutines before Auth sign-out
 * or before a replacement session starts (avoids Firebase/SQLite "destroyed mutex" races).
 */
object GoogleLinkCoordinator {
    private val gate = Mutex()
    private val applyMutex = Mutex()

    /** Process-lifetime launcher for app-start restore only; never cancelled on unlink. */
    private val bootstrapScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sessionJob: Job = SupervisorJob()
    private var sessionScope: CoroutineScope = CoroutineScope(sessionJob + Dispatchers.Default)

    @Volatile
    private var sessionEpoch: Long = 0L

    @Volatile
    private var cloudOpsActive: Boolean = false

    /** Last cloud registry ids seen — used to propagate explicit cloud removals locally. */
    @Volatile
    private var lastCloudRegistryIds: Set<String> = emptySet()

    /** Cached cloud device rows for FCM wake target resolution. */
    @Volatile
    private var cachedCloudRecords: List<CloudDeviceRecord> = emptyList()

    /** Last presence successfully published (network fields only; ignores updatedAt). */
    @Volatile
    private var lastPublishedPresence: CloudDevicePresence? = null

    private var registryHandle: CloudRegistryHandle? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun onAppLaunch() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        bootstrapScope.launch {
            runCatching { restoreSessionAndListen() }
                .onFailure { error ->
                    _status.value = error.message ?: "Cloud link restore failed"
                    println("GoogleLinkCoordinator: restore failed — ${error.message}")
                }
        }
    }

    /** One-shot cloud presence publish on cold launch or foreground refresh. */
    suspend fun publishSelfPresenceIfLinked() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        runCatching { patchSelfPresence(uid) }
            .onFailure { error ->
                println("GoogleLinkCoordinator: presence publish failed — ${error.message}")
            }
    }

    /**
     * Scheduled Firestore heartbeat — always refreshes `updatedAtEpochMs` even when LAN fields
     * are unchanged so peers can show Ready/Tap to wake from cloud last_seen.
     */
    suspend fun publishScheduledPresenceHeartbeat() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        runCatching {
            val next = buildSelfPresence()
            CloudAuthBackend.patchDevicePresence(uid, next)
            lastPublishedPresence = next
        }.onFailure { error ->
            println("GoogleLinkCoordinator: scheduled heartbeat failed — ${error.message}")
        }
    }

    /** Clears dedupe cache so the next publish runs after LAN/network transitions. */
    fun invalidatePublishedPresenceCache() {
        lastPublishedPresence = null
    }

    /** FCM wake targets — cloud-linked Android peers with a registered token. */
    fun linkedPeerFcmTargets(selfDeviceId: String): List<FcmWakeTarget> =
        cachedCloudRecords.asSequence()
            .filter { record ->
                record.deviceId.isNotBlank() &&
                    record.deviceId != selfDeviceId &&
                    record.platform.equals("android", ignoreCase = true) &&
                    record.fcmToken.isNotBlank()
            }
            .map { record -> FcmWakeTarget(record.deviceId, record.fcmToken) }
            .toList()

    /** Registers/refreshes this device's FCM token in Firestore when cloud-linked. */
    suspend fun patchSelfFcmToken(fcmToken: String) {
        val trimmed = fcmToken.trim()
        if (trimmed.isEmpty()) return
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        runCatching {
            CloudAuthBackend.patchDeviceFcmToken(uid, loadLocalIdentity().deviceId, trimmed)
        }.onFailure { error ->
            println("GoogleLinkCoordinator: FCM token patch failed — ${error.message}")
        }
    }

    /**
     * Complete link after platform OAuth / Credential Manager yields a Google ID token.
     */
    suspend fun linkWithGoogleIdToken(idToken: String, emailHint: String?): GoogleAuthSession {
        gate.withLock {
            require(CloudAuthBackend.isConfigured()) {
                "Set fileapex.google.web.client.id in gradle.properties (Google Web OAuth client ID)"
            }
            require(idToken.isNotBlank()) { "Missing Google ID token" }
            _status.value = "Signing in…"
            shutdownCloudSessionLocked()
            val session = CloudAuthBackend.signInWithGoogleIdToken(idToken)
            val email = session.email.ifBlank { emailHint.orEmpty() }
            val settings = FileApexServices.settings
            settings.setGoogleAccountEmail(email)
            settings.setGoogleAccountUid(session.firebaseUid)
            settings.setGoogleAccountLinkEnabled(true)
            registerSelf(session.firebaseUid)
            startCloudSessionLocked(session.firebaseUid)
            _status.value = "Linked as ${email.ifBlank { session.firebaseUid }}"
            return session.copy(email = email)
        }
    }

    suspend fun unlinkAndSignOut() {
        gate.withLock {
            _status.value = "Signing out…"
            val uid = FileApexServices.settings.googleAccountUid.value
            val deviceId = loadLocalIdentity().deviceId
            // Drain listeners/workers before any Auth/Firestore mutation or sign-out.
            shutdownCloudSessionLocked()
            if (uid.isNotBlank()) {
                runCatching { CloudAuthBackend.deleteDevice(uid, deviceId) }
            }
            runCatching { CloudAuthBackend.signOut() }
            FileApexServices.settings.setGoogleAccountLinkEnabled(false)
            _status.value = "Google Account unlinked"
        }
    }

    suspend fun publishRemovedPeer(deviceId: String) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        val cloudId = resolveCloudDeviceId(deviceId)
        runCatching { CloudAuthBackend.deleteDevice(uid, cloudId) }
            .onFailure { error ->
                println("GoogleLinkCoordinator: cloud remove failed — ${error.message}")
            }
    }

    /**
     * Explicit user rename → Firestore `deviceName` field patch only.
     * [deviceId] may be [LocalIdentity.LOCAL_DEVICE_ID] or a peer cloud/local device id.
     */
    suspend fun publishUserRenamedDevice(deviceId: String, newName: String) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
        val cloudDeviceId = resolveCloudDeviceId(deviceId)
        CloudAuthBackend.patchDeviceName(
            uid = uid,
            deviceId = cloudDeviceId,
            deviceName = trimmed,
            updatedAtEpochMs = TimestampDiagnostics.mutatingNow(
                "GoogleLinkCoordinator.publishUserRenamedDevice.updatedAtEpochMs"
            )
        )
    }

    private suspend fun restoreSessionAndListen() {
        gate.withLock {
            val session = CloudAuthBackend.currentSession()
                ?: error("No Firebase session — sign in again")
            val settings = FileApexServices.settings
            if (settings.googleAccountEmail.value.isBlank() && session.email.isNotBlank()) {
                settings.setGoogleAccountEmail(session.email)
            }
            settings.setGoogleAccountUid(session.firebaseUid)
            shutdownCloudSessionLocked()
            // Presence only on restore — never overwrite remote deviceName with stale local memory.
            runCatching { patchSelfPresence(session.firebaseUid) }
            startCloudSessionLocked(session.firebaseUid)
            _status.value = "Cloud registry active"
        }
    }

    /**
     * Invalidate epoch, detach Firestore listener, and join all session work
     * before Auth teardown or a new session attaches.
     */
    private suspend fun shutdownCloudSessionLocked() {
        sessionEpoch += 1L
        cloudOpsActive = false
        lastPublishedPresence = null
        lastCloudRegistryIds = emptySet()
        cachedCloudRecords = emptyList()

        val previousHandle = registryHandle
        registryHandle = null
        previousHandle?.stop()
        previousHandle?.awaitIdle()

        val previousJob = sessionJob
        sessionJob = SupervisorJob()
        sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)
        previousJob.cancelAndJoin()

        // Brief settle so native Firebase/SQLite mutexes are not reused mid-destroy.
        delay(SESSION_SETTLE_MS)
    }

    private fun startCloudSessionLocked(uid: String) {
        cloudOpsActive = true
        val epoch = sessionEpoch
        val selfId = loadLocalIdentity().deviceId
        val scope = sessionScope

        scope.launch {
            if (!isSessionLive(epoch)) return@launch
            runCatching {
                FileApexServices.deviceRepositoryOrNull()?.reconcileDuplicateEndpoints()
            }.onFailure { error ->
                println("GoogleLinkCoordinator: reconcile failed — ${error.message}")
            }
        }

        registryHandle = CloudAuthBackend.observeUserDevices(
            uid = uid,
            onDevices = { records ->
                if (!isSessionLive(epoch)) return@observeUserDevices
                scope.launch {
                    if (!isSessionLive(epoch)) return@launch
                    applyRemoteDevices(records, selfId, epoch)
                }
            },
            onError = { error ->
                if (isSessionLive(epoch)) {
                    _status.value = error.message ?: "Cloud registry error"
                    println("GoogleLinkCoordinator: observe error — ${error.message}")
                }
            }
        )
    }

    private fun isSessionLive(epoch: Long): Boolean =
        cloudOpsActive && epoch == sessionEpoch && FileApexServices.isDatabaseReady()

    private suspend fun registerSelf(uid: String) {
        val record = buildSelfRecord()
        CloudAuthBackend.registerDevice(uid, record)
        lastPublishedPresence = buildSelfPresence().copy(
            updatedAtEpochMs = record.updatedAtEpochMs
        )
    }

    private suspend fun patchSelfPresence(uid: String) {
        if (!cloudOpsActive) return
        val next = buildSelfPresence()
        val previous = lastPublishedPresence
        if (previous != null && previous.sameNetworkFieldsAs(next)) {
            // No LAN/identity change — skip Firestore write so listeners (and UIs) stay quiet.
            return
        }
        CloudAuthBackend.patchDevicePresence(uid, next)
        lastPublishedPresence = next
    }

    private fun buildSelfRecord(): CloudDeviceRecord {
        val presence = buildSelfPresence()
        val name = LocalDeviceNameStore.current().ifBlank { loadLocalIdentity().deviceName }
        return CloudDeviceRecord(
            deviceId = presence.deviceId,
            deviceName = name,
            lastKnownIp = presence.lastKnownIp,
            port = presence.port,
            publicKeyHash = presence.publicKeyHash,
            rootPath = presence.rootPath,
            platform = presence.platform,
            clientVersion = presence.clientVersion,
            clientVersionCode = presence.clientVersionCode,
            updatedAtEpochMs = presence.updatedAtEpochMs
        )
    }

    private fun buildSelfPresence(): CloudDevicePresence {
        val identity = loadLocalIdentity()
        // Stable pick: sorted IPv4 list so heartbeats do not flip between interfaces.
        val host = NetworkUtils.preferredLanIpv4()
        return CloudDevicePresence(
            deviceId = identity.deviceId,
            lastKnownIp = host,
            port = identity.sharePort,
            publicKeyHash = DeviceIdentityMarkers.fingerprint(identity.deviceId),
            rootPath = identity.rootPath,
            platform = currentPlatformLabel(),
            clientVersion = currentAppVersionName(),
            clientVersionCode = currentAppVersionCode(),
            updatedAtEpochMs = TimestampDiagnostics.mutatingNow(
                "GoogleLinkCoordinator.buildSelfPresence.updatedAtEpochMs"
            )
        )
    }

    /**
     * Remote snapshot seeds peers into Room. Never writes Firestore from here.
     *
     * This device's display name is owned locally (UI rename or POST /identity/rename).
     * Firestore / peer-roster copies of our name are ignored so stale snapshots cannot
     * toggle "This device" after a rename.
     */
    private suspend fun applyRemoteDevices(
        records: List<CloudDeviceRecord>,
        selfId: String,
        epoch: Long
    ) {
        applyMutex.withLock {
            if (!isSessionLive(epoch)) return
            val repo = FileApexServices.deviceRepositoryOrNull() ?: return
            cachedCloudRecords = records
            val remoteIds = records.map { it.deviceId }.filter { it.isNotBlank() }.toSet()
            if (lastCloudRegistryIds.isNotEmpty()) {
                val removedFromCloud = lastCloudRegistryIds - remoteIds - selfId
                for (vanishedId in removedFromCloud) {
                    if (!isSessionLive(epoch)) return
                    val local = repo.getDevice(vanishedId)
                    runCatching {
                        repo.applyRemoteRemoval(
                            RemovedDeviceRecord(
                                deviceId = vanishedId,
                                publicKeyHash = local?.publicKeyHash.orEmpty(),
                                lastKnownIp = local?.lastKnownIp.orEmpty(),
                                port = local?.port ?: 0
                            )
                        )
                    }.onFailure { error ->
                        println(
                            "GoogleLinkCoordinator: cloud removal apply failed for " +
                                "$vanishedId — ${error.message}"
                        )
                    }
                }
            }
            lastCloudRegistryIds = remoteIds
            // Apply peers with usable LAN endpoints first so blank-IP stubs merge into them
            // instead of temporarily winning and deleting the good row.
            records.asSequence()
                .filter { it.deviceId.isNotBlank() }
                .sortedBy { record ->
                    val ip = record.lastKnownIp.trim()
                    if (ip.isEmpty() || ip == "127.0.0.1" || ip == "0.0.0.0") 1 else 0
                }
                .forEach { remote ->
                    if (!isSessionLive(epoch)) return
                    if (remote.deviceId == selfId) {
                        // Publish our name TO the cloud on rename; never import OUR name FROM cloud.
                        return@forEach
                    }
                    val local = repo.getDevice(remote.deviceId)
                    runCatching {
                        if (!isSessionLive(epoch)) return@runCatching
                        repo.upsertReplacingAliases(
                            PairedDeviceEntity(
                                deviceId = remote.deviceId,
                                deviceName = remote.deviceName.ifBlank { "Cloud device" },
                                lastKnownIp = remote.lastKnownIp,
                                port = remote.port,
                                publicKeyHash = remote.publicKeyHash,
                                rootPath = remote.rootPath.ifBlank { "/" },
                                clientVersion = remote.clientVersion.ifBlank {
                                    local?.clientVersion.orEmpty()
                                },
                                clientVersionCode = remote.clientVersionCode.takeIf { it > 0 }
                                    ?: local?.clientVersionCode
                                    ?: 0,
                                platform = remote.platform.ifBlank { local?.platform.orEmpty() },
                                lastSeenEpochMs = remote.updatedAtEpochMs.coerceAtLeast(0L)
                            )
                        )
                        if (remote.updatedAtEpochMs > 0L && FileApexServices.isDatabaseReady()) {
                            FileApexServices.presenceMonitor.notifyPassiveReachability(
                                remote.deviceId,
                                epochMs = remote.updatedAtEpochMs
                            )
                        }
                    }.onFailure { error ->
                        println(
                            "GoogleLinkCoordinator: skip Room upsert after teardown — ${error.message}"
                        )
                    }
                }
            runCatching { repo.reconcileDuplicateEndpoints() }
        }
    }

    private fun resolveCloudDeviceId(deviceId: String): String {
        if (deviceId == LocalIdentity.LOCAL_DEVICE_ID) {
            return loadLocalIdentity().deviceId
        }
        return deviceId
    }

    private fun CloudDevicePresence.sameNetworkFieldsAs(other: CloudDevicePresence): Boolean =
        deviceId == other.deviceId &&
            lastKnownIp == other.lastKnownIp &&
            port == other.port &&
            publicKeyHash == other.publicKeyHash &&
            rootPath == other.rootPath &&
            platform == other.platform &&
            clientVersion == other.clientVersion &&
            clientVersionCode == other.clientVersionCode

    private const val SESSION_SETTLE_MS = 50L
}

expect fun currentPlatformLabel(): String
