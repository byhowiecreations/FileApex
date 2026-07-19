package com.omninode.cloud

import com.omninode.data.db.PairedDeviceEntity
import com.omninode.data.identity.LocalDeviceNameStore
import com.omninode.data.identity.loadLocalIdentity
import com.omninode.di.OmniNodeServices
import com.omninode.platform.currentTimeMillis
import com.omninode.platform.localIpv4Addresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in Google Account linking + Firestore virtual device registry.
 * Single source of truth for cloud pairing seed → local [com.omninode.data.device.DeviceRepository].
 */
object GoogleLinkCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = Mutex()
    private var registryHandle: CloudRegistryHandle? = null
    private var heartbeatJob: Job? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun onAppLaunch() {
        if (!OmniNodeServices.settings.googleAccountLinkEnabled.value) return
        scope.launch {
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
            val session = CloudAuthBackend.signInWithGoogleIdToken(idToken)
            val email = session.email.ifBlank { emailHint.orEmpty() }
            val settings = OmniNodeServices.settings
            settings.setGoogleAccountEmail(email)
            settings.setGoogleAccountUid(session.firebaseUid)
            settings.setGoogleAccountLinkEnabled(true)
            publishSelf(session.firebaseUid)
            startListening(session.firebaseUid)
            startHeartbeat(session.firebaseUid)
            _status.value = "Linked as ${email.ifBlank { session.firebaseUid }}"
            return session.copy(email = email)
        }
    }

    suspend fun unlinkAndSignOut() {
        gate.withLock {
            _status.value = "Signing out…"
            stopListening()
            heartbeatJob?.cancel()
            heartbeatJob = null
            val uid = OmniNodeServices.settings.googleAccountUid.value
            val deviceId = loadLocalIdentity().deviceId
            if (uid.isNotBlank()) {
                runCatching { CloudAuthBackend.deleteDevice(uid, deviceId) }
            }
            runCatching { CloudAuthBackend.signOut() }
            OmniNodeServices.settings.setGoogleAccountLinkEnabled(false)
            _status.value = "Google Account unlinked"
        }
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
            publishSelf(session.firebaseUid)
            startListening(session.firebaseUid)
            startHeartbeat(session.firebaseUid)
            _status.value = "Cloud registry active"
        }
    }

    private suspend fun publishSelf(uid: String) {
        CloudAuthBackend.publishDevice(uid, buildSelfRecord())
    }

    private fun buildSelfRecord(): CloudDeviceRecord {
        val identity = loadLocalIdentity()
        val host = localIpv4Addresses().firstOrNull() ?: "127.0.0.1"
        val name = LocalDeviceNameStore.current().ifBlank { identity.deviceName }
        return CloudDeviceRecord(
            deviceId = identity.deviceId,
            deviceName = name,
            lastKnownIp = host,
            port = identity.sharePort,
            publicKeyHash = deviceFingerprint(identity.deviceId),
            rootPath = identity.rootPath,
            platform = currentPlatformLabel(),
            updatedAtEpochMs = currentTimeMillis()
        )
    }

    private fun startListening(uid: String) {
        stopListening()
        val selfId = loadLocalIdentity().deviceId
        registryHandle = CloudAuthBackend.observeUserDevices(
            uid = uid,
            excludeDeviceId = selfId,
            onDevices = { records ->
                scope.launch {
                    mergeCloudDevices(records, selfId)
                }
            },
            onError = { error ->
                _status.value = error.message ?: "Cloud registry error"
                println("GoogleLinkCoordinator: observe error — ${error.message}")
            }
        )
    }

    private fun stopListening() {
        registryHandle?.stop()
        registryHandle = null
    }

    private fun startHeartbeat(uid: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(HEARTBEAT_MS)
                runCatching { publishSelf(uid) }
            }
        }
    }

    private suspend fun mergeCloudDevices(records: List<CloudDeviceRecord>, selfId: String) {
        val repo = OmniNodeServices.deviceRepository
        records.asSequence()
            .filter { it.deviceId.isNotBlank() && it.deviceId != selfId }
            .forEach { remote ->
                repo.upsert(
                    PairedDeviceEntity(
                        deviceId = remote.deviceId,
                        deviceName = remote.deviceName.ifBlank { "Cloud device" },
                        lastKnownIp = remote.lastKnownIp,
                        port = remote.port,
                        publicKeyHash = remote.publicKeyHash,
                        rootPath = remote.rootPath.ifBlank { "/" }
                    )
                )
            }
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

    private const val HEARTBEAT_MS = 60_000L
}

expect fun currentPlatformLabel(): String
