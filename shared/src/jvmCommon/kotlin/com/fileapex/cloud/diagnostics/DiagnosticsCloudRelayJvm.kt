package com.fileapex.cloud.diagnostics

import com.fileapex.cloud.CloudAuthBackend
import com.fileapex.cloud.CloudDeviceRecord
import com.fileapex.cloud.CloudRegistryHandle
import com.fileapex.cloud.FcmWakeBackend
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.network.FileApexHttpClientFactory
import com.fileapex.platform.collectDeviceDiagnostics
import com.fileapex.util.TimeUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** JVM implementation shared by Android and desktop (Mac/Windows). */
internal object DiagnosticsCloudRelayJvm {
    private val json = FileApexHttpClientFactory.defaultJson
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val respondMutex = Mutex()
    private val inFlightSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var inboxHandle: CloudRegistryHandle? = null

    @Volatile
    private var inboxUid: String = ""

    @Volatile
    private var inboxSelfId: String = ""

    suspend fun fetchPeerDiagnostics(peerDeviceId: String): PeerDeviceDiagnostics {
        val settings = FileApexServices.settings
        if (!settings.deviceDetailsAllowOverCellular.value) {
            error(DiagnosticsRelayErrors.localOptInRequired())
        }
        if (!settings.googleAccountLinkEnabled.value) {
            error(DiagnosticsRelayErrors.googleLinkRequired())
        }
        val uid = settings.googleAccountUid.value.trim()
        if (uid.isBlank()) {
            error(DiagnosticsRelayErrors.googleLinkRequired())
        }
        val peer = resolveCloudDeviceRecord(uid, peerDeviceId)
            ?: error(DiagnosticsRelayErrors.peerNotCloudLinked())
        if (!peer.deviceDetailsCloudEnabled) {
            error(DiagnosticsRelayErrors.peerOptInRequired())
        }
        val peerPublicKey = peer.diagnosticsPublicKey.trim()
        if (peerPublicKey.isBlank()) {
            error(DiagnosticsRelayErrors.peerKeyMissing())
        }

        val selfId = loadLocalIdentity().deviceId
        syncCloudOptIn(uid, selfId, enabled = true)
        val keyPair = DiagnosticsIdentityStore.ensureKeyPair()
        val sessionId = UUID.randomUUID().toString()
        val now = TimeUtils.now()
        val requestPlain = json.encodeToString(DiagnosticsRelayRequest())
        val requestEnc = DiagnosticsCrypto.encrypt(
            plaintext = requestPlain.encodeToByteArray(),
            localPrivateKey = keyPair.privateKey,
            peerPublicKey = DiagnosticsCrypto.decodePublicKey(peerPublicKey),
            googleUid = uid
        )
        val session = DiagnosticsRelaySession(
            sessionId = sessionId,
            requesterDeviceId = selfId,
            responderDeviceId = peerDeviceId,
            requestEncPayload = requestEnc,
            status = DiagnosticsRelayStatus.PENDING,
            createdAtEpochMs = now,
            ttlEpochMs = now + DIAGNOSTICS_RELAY_TTL_MS
        )
        CloudAuthBackend.upsertDiagnosticsRelaySession(uid, session)

        wakeAndroidResponder(peer, selfId, sessionId)

        val deadline = now + DIAGNOSTICS_RELAY_FETCH_TIMEOUT_MS
        while (TimeUtils.now() < deadline) {
            val current = CloudAuthBackend.fetchDiagnosticsRelaySession(uid, sessionId)
            when {
                current != null && current.status == DiagnosticsRelayStatus.COMPLETE &&
                    current.responseEncPayload.isNotBlank() -> {
                    val plain = DiagnosticsCrypto.decrypt(
                        payloadBase64 = current.responseEncPayload,
                        localPrivateKey = keyPair.privateKey,
                        peerPublicKey = DiagnosticsCrypto.decodePublicKey(peerPublicKey),
                        googleUid = uid
                    )
                    runCatching { CloudAuthBackend.deleteDiagnosticsRelaySession(uid, sessionId) }
                    return json.decodeFromString(plain.decodeToString())
                }
                current?.status == DiagnosticsRelayStatus.FAILED -> {
                    runCatching { CloudAuthBackend.deleteDiagnosticsRelaySession(uid, sessionId) }
                    error(DiagnosticsRelayErrors.peerRespondedFailed())
                }
                current?.isExpired(TimeUtils.now()) == true -> {
                    runCatching { CloudAuthBackend.deleteDiagnosticsRelaySession(uid, sessionId) }
                    error(DiagnosticsRelayErrors.timedOut())
                }
            }
            delay(DIAGNOSTICS_RELAY_POLL_MS)
        }
        runCatching { CloudAuthBackend.deleteDiagnosticsRelaySession(uid, sessionId) }
        error(DiagnosticsRelayErrors.timedOut())
    }

    private suspend fun wakeAndroidResponder(
        peer: CloudDeviceRecord,
        sourceDeviceId: String,
        sessionId: String
    ) {
        if (!peer.platform.equals("android", ignoreCase = true)) return
        val token = peer.fcmToken.trim()
        if (token.isBlank()) {
            error(DiagnosticsRelayErrors.peerFcmTokenMissing())
        }
        if (!FcmWakeBackend.sendDiagnosticsWake(
                targetFcmToken = token,
                sourceDeviceId = sourceDeviceId,
                sessionId = sessionId
            )
        ) {
            println("DiagnosticsCloudRelay: FCM wake send failed - polling relay anyway")
        }
    }

    suspend fun syncCloudOptIn(uid: String, deviceId: String, enabled: Boolean) {
        if (uid.isBlank() || deviceId.isBlank()) return
        if (enabled) {
            val keys = DiagnosticsIdentityStore.ensureKeyPair()
            CloudAuthBackend.patchDeviceDiagnosticsCloud(
                uid = uid,
                deviceId = deviceId,
                diagnosticsPublicKey = keys.publicKeyBase64(),
                deviceDetailsCloudEnabled = true
            )
        } else {
            CloudAuthBackend.patchDeviceDiagnosticsCloud(
                uid = uid,
                deviceId = deviceId,
                diagnosticsPublicKey = "",
                deviceDetailsCloudEnabled = false
            )
        }
    }

    fun startInbox(uid: String, selfDeviceId: String) {
        if (uid.isBlank() || selfDeviceId.isBlank()) return
        if (!FileApexServices.settings.deviceDetailsAllowOverCellular.value) {
            stopInbox()
            return
        }
        if (inboxUid == uid && inboxSelfId == selfDeviceId && inboxHandle != null) {
            return
        }
        stopInbox()
        inboxUid = uid
        inboxSelfId = selfDeviceId
        inboxHandle = CloudAuthBackend.observeDiagnosticsRelayInbox(
            uid = uid,
            responderDeviceId = selfDeviceId,
            onSession = { session ->
                scope.launch {
                    respondToSession(uid, session)
                }
            },
            onError = { error ->
                println("DiagnosticsCloudRelay: inbox error - ${error.message}")
            }
        )
    }

    fun stopInbox() {
        inboxHandle?.stop()
        inboxHandle = null
        inboxUid = ""
        inboxSelfId = ""
        inFlightSessionIds.clear()
    }

    fun onDiagnosticsWake(sessionId: String) {
        val trimmed = sessionId.trim()
        if (trimmed.isEmpty()) return
        val uid = FileApexServices.settings.googleAccountUid.value.trim()
        if (uid.isBlank()) return
        val selfId = loadLocalIdentity().deviceId
        if (inboxHandle == null && FileApexServices.settings.deviceDetailsAllowOverCellular.value) {
            startInbox(uid, selfId)
        }
        scope.launch {
            val session = CloudAuthBackend.fetchDiagnosticsRelaySession(uid, trimmed) ?: return@launch
            respondToSession(uid, session)
        }
    }

    private suspend fun resolveCloudDeviceRecord(uid: String, deviceId: String): CloudDeviceRecord? {
        return runCatching { CloudAuthBackend.fetchCloudDeviceRecord(uid, deviceId) }
            .getOrNull()
            ?: GoogleLinkCoordinator.cloudRecordFor(deviceId)
    }

    private suspend fun respondToSession(uid: String, session: DiagnosticsRelaySession) {
        if (session.status != DiagnosticsRelayStatus.PENDING) return
        if (session.isExpired(TimeUtils.now())) {
            runCatching { CloudAuthBackend.deleteDiagnosticsRelaySession(uid, session.sessionId) }
            return
        }
        if (!FileApexServices.settings.deviceDetailsAllowOverCellular.value) return
        val selfId = loadLocalIdentity().deviceId
        if (session.responderDeviceId != selfId) return
        if (!inFlightSessionIds.add(session.sessionId)) return

        respondMutex.withLock {
            try {
                val requester = resolveCloudDeviceRecord(uid, session.requesterDeviceId)
                if (requester == null) {
                    CloudAuthBackend.failDiagnosticsRelaySession(uid, session.sessionId)
                    return
                }
                if (!requester.deviceDetailsCloudEnabled) {
                    CloudAuthBackend.failDiagnosticsRelaySession(uid, session.sessionId)
                    return
                }
                val requesterKey = requester.diagnosticsPublicKey.trim()
                if (requesterKey.isBlank()) {
                    CloudAuthBackend.failDiagnosticsRelaySession(uid, session.sessionId)
                    return
                }

                val keyPair = DiagnosticsIdentityStore.ensureKeyPair()
                syncCloudOptIn(uid, selfId, enabled = true)

                runCatching {
                    DiagnosticsCrypto.decrypt(
                        payloadBase64 = session.requestEncPayload,
                        localPrivateKey = keyPair.privateKey,
                        peerPublicKey = DiagnosticsCrypto.decodePublicKey(requesterKey),
                        googleUid = uid
                    )
                }.getOrElse {
                    println("DiagnosticsCloudRelay: request decrypt failed - ${it.message}")
                    CloudAuthBackend.failDiagnosticsRelaySession(uid, session.sessionId)
                    return
                }

                val snapshot = withContext(Dispatchers.IO) { collectDeviceDiagnostics() }
                val responsePlain = json.encodeToString(snapshot)
                val responseEnc = DiagnosticsCrypto.encrypt(
                    plaintext = responsePlain.encodeToByteArray(),
                    localPrivateKey = keyPair.privateKey,
                    peerPublicKey = DiagnosticsCrypto.decodePublicKey(requesterKey),
                    googleUid = uid
                )
                CloudAuthBackend.completeDiagnosticsRelaySession(
                    uid = uid,
                    sessionId = session.sessionId,
                    responseEncPayload = responseEnc
                )
            } catch (error: Throwable) {
                println("DiagnosticsCloudRelay: respond failed - ${error.message}")
                runCatching { CloudAuthBackend.failDiagnosticsRelaySession(uid, session.sessionId) }
            } finally {
                inFlightSessionIds.remove(session.sessionId)
            }
        }
    }
}
