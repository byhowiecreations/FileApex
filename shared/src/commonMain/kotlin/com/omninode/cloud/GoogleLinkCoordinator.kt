package com.omninode.cloud

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.LocalIdentity
import com.omninode.data.identity.loadLocalIdentity
import com.omninode.di.OmniNodeServices
import com.omninode.platform.currentTimeMillis
import com.omninode.platform.localIpv4Addresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in Google Account linking + Firestore virtual device registry.
 * Single source of truth for cloud pairing seed → local [com.omninode.data.device.DeviceRepository].
 *
 * [deviceName] is written to Firestore only from explicit user rename actions
 * ([publishUserRenamedDevice]). Heartbeats patch presence fields only, and only when those
 * fields actually change (never spam updatedAt-only writes that thrash listeners/UI).
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

    /** Last presence successfully published (network fields only; ignores updatedAt). */
    @Volatile
    private var lastPublishedPresence: CloudDevicePresence? = null

    private var registryHandle: CloudRegistryHandle? = null
    private var heartbeatJob: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun onAppLaunch() {
        if (!OmniNodeServices.settings.googleAccountLinkEnabled.value) return
        bootstrapScope.launch {
            runCatching { restoreSessionAndListen() }
                .onFailure { error ->
                    _status.value = error.message ?: "Cloud link restore failed"
                    println("GoogleLinkCoordinator: restore failed — ${error.message}")
                }
        }
    }

    /**
     * Complete link after platform OAuth / Credential Manager yields a Google ID token.
     */
    suspend fun linkWithGoogleIdToken(idToken: String, emailHint: String?): GoogleAuthSession {
        gate.withLock {
            require(CloudAuthBackend.isConfigured()) {
                "Set omninode.google.web.client.id in gradle.properties (Google Web OAuth client ID)"
            }
            require(idToken.isNotBlank()) { "Missing Google ID token" }
            _status.value = "Signing in…"
            shutdownCloudSessionLocked()
            val session = CloudAuthBackend.signInWithGoogleIdToken(idToken)
            val email = session.email.ifBlank { emailHint.orEmpty() }
            val settings = OmniNodeServices.settings
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
            val uid = OmniNodeServices.settings.googleAccountUid.value
            val deviceId = loadLocalIdentity().deviceId
            // Drain listeners/workers before any Auth/Firestore mutation or sign-out.
            shutdownCloudSessionLocked()
            if (uid.isNotBlank()) {
                runCatching { CloudAuthBackend.deleteDevice(uid, deviceId) }
            }
            runCatching { CloudAuthBackend.signOut() }
            OmniNodeServices.settings.setGoogleAccountLinkEnabled(false)
            _status.value = "Google Account unlinked"
        }
    }

    /**
     * Explicit user rename → Firestore `deviceName` field patch only.
     * [deviceId] may be [LocalIdentity.LOCAL_DEVICE_ID] or a peer cloud/local device id.
     */
    suspend fun publishUserRenamedDevice(deviceId: String, newName: String) {
        if (!OmniNodeServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = OmniNodeServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "Device name cannot be empty" }
        val cloudDeviceId = resolveCloudDeviceId(deviceId)
        CloudAuthBackend.patchDeviceName(
            uid = uid,
            deviceId = cloudDeviceId,
            deviceName = trimmed,
            updatedAtEpochMs = currentTimeMillis()
        )
    }

    private suspend fun restoreSessionAndListen() {
        gate.withLock {
            val session = CloudAuthBackend.currentSession()
                ?: error("No Firebase session — sign in again")
            val settings = OmniNodeServices.settings
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
     * Invalidate epoch, cancel heartbeat, detach Firestore listener, and join all session work
     * before Auth teardown or a new session attaches.
     */
    private suspend fun shutdownCloudSessionLocked() {
        sessionEpoch += 1L
        cloudOpsActive = false
        lastPublishedPresence = null

        val previousHeartbeat = heartbeatJob
        heartbeatJob = null
        previousHeartbeat?.cancelAndJoin()

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
                OmniNodeServices.deviceRepositoryOrNull()?.reconcileDuplicateEndpoints()
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

        heartbeatJob = scope.launch {
            while (isActive && isSessionLive(epoch)) {
                delay(HEARTBEAT_MS)
                if (!isSessionLive(epoch)) break
                runCatching { patchSelfPresence(uid) }
            }
        }
    }

    private fun isSessionLive(epoch: Long): Boolean =
        cloudOpsActive && epoch == sessionEpoch && OmniNodeServices.isDatabaseReady()

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
            updatedAtEpochMs = presence.updatedAtEpochMs
        )
    }

    private fun buildSelfPresence(): CloudDevicePresence {
        val identity = loadLocalIdentity()
        // Stable pick: sorted IPv4 list so heartbeats do not flip between interfaces.
        val host = localIpv4Addresses().sorted().firstOrNull() ?: "127.0.0.1"
        return CloudDevicePresence(
            deviceId = identity.deviceId,
            lastKnownIp = host,
            port = identity.sharePort,
            publicKeyHash = deviceFingerprint(identity.deviceId),
            rootPath = identity.rootPath,
            platform = currentPlatformLabel(),
            updatedAtEpochMs = currentTimeMillis()
        )
    }

    /**
     * Remote snapshot wins for local Room / display name. Never writes Firestore from here.
     * Skips Room writes when peer rows are already identical (stops UI jitter).
     */
    private suspend fun applyRemoteDevices(
        records: List<CloudDeviceRecord>,
        selfId: String,
        epoch: Long
    ) {
        applyMutex.withLock {
            if (!isSessionLive(epoch)) return
            val repo = OmniNodeServices.deviceRepositoryOrNull() ?: return
            records.asSequence()
                .filter { it.deviceId.isNotBlank() }
                .forEach { remote ->
                    if (!isSessionLive(epoch)) return
                    if (remote.deviceId == selfId) {
                        // Local display name yields to Firestore (rename initiated elsewhere).
                        applyRemoteSelfName(remote.deviceName, epoch)
                        return@forEach
                    }
                    runCatching {
                        if (!isSessionLive(epoch)) return@runCatching
                        repo.upsertReplacingAliases(
                            PairedDeviceEntity(
                                deviceId = remote.deviceId,
                                deviceName = remote.deviceName.ifBlank { "Cloud device" },
                                lastKnownIp = remote.lastKnownIp,
                                port = remote.port,
                                publicKeyHash = remote.publicKeyHash,
                                rootPath = remote.rootPath.ifBlank { "/" }
                            )
                        )
                    }.onFailure { error ->
                        println(
                            "GoogleLinkCoordinator: skip Room upsert after teardown — ${error.message}"
                        )
                    }
                }
        }
    }

    private fun applyRemoteSelfName(remoteName: String, epoch: Long) {
        if (!isSessionLive(epoch)) return
        val trimmed = remoteName.trim()
        if (trimmed.isEmpty()) return
        if (trimmed == LocalDeviceNameStore.current()) return
        // Accept remote rename without pushing back to Firestore.
        runCatching { LocalDeviceNameStore.apply(trimmed) }
            .onFailure { error ->
                println(
                    "GoogleLinkCoordinator: skip local name apply after teardown — ${error.message}"
                )
            }
    }

    private fun resolveCloudDeviceId(deviceId: String): String {
        if (deviceId == LocalIdentity.LOCAL_DEVICE_ID) {
            return loadLocalIdentity().deviceId
        }
        return deviceId
    }

    private fun deviceFingerprint(deviceId: String): String {
        // Identity marker only — not a signing key; never used for file content.
        var hash = 0xcbf29ce484222325UL
        val prime = 0x100000001b3UL
        for (ch in deviceId) {
            hash = hash xor ch.code.toULong()
            hash *= prime
        }
        return hash.toString(16).padStart(16, '0')
    }

    private fun CloudDevicePresence.sameNetworkFieldsAs(other: CloudDevicePresence): Boolean =
        deviceId == other.deviceId &&
            lastKnownIp == other.lastKnownIp &&
            port == other.port &&
            publicKeyHash == other.publicKeyHash &&
            rootPath == other.rootPath &&
            platform == other.platform

    private const val HEARTBEAT_MS = 60_000L
    private const val SESSION_SETTLE_MS = 50L
}

expect fun currentPlatformLabel(): String
