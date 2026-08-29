package com.fileapex.cloud

import com.fileapex.cloud.diagnostics.DiagnosticsCloudRelay
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.device.DeviceDisplayNames
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.localDeviceHardwareProfile
import com.fileapex.util.DeviceIdentityMarkers
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
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
 * Cloud pairing seed feeds local [com.fileapex.data.device.DeviceRepository].
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

    @Volatile
    private var cachedCloudRecords: List<CloudDeviceRecord> = emptyList()

    fun cloudRecordFor(deviceId: String): CloudDeviceRecord? =
        cachedCloudRecords.find { it.deviceId == deviceId }

    /** Last presence successfully published (network fields only; ignores updatedAt). */
    @Volatile
    private var lastPublishedPresence: CloudDevicePresence? = null

    private var registryHandle: CloudRegistryHandle? = null

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun onAppLaunch() {
        bootstrapScope.launch {
            runCatching { restoreSessionZeroTap() }
                .onFailure { error ->
                    _status.value = error.message ?: "Cloud link restore failed"
                    println("GoogleLinkCoordinator: restore failed - ${error.message}")
                }
        }
    }

    suspend fun publishSelfPresenceIfLinked() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        runCatching { patchSelfPresence(uid) }
            .onFailure { error ->
                println("GoogleLinkCoordinator: presence publish failed - ${error.message}")
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
            println("GoogleLinkCoordinator: scheduled heartbeat failed - ${error.message}")
        }
    }

    fun invalidatePublishedPresenceCache() {
        lastPublishedPresence = null
    }

    /** Force a Firestore roster read into Room (desktop poll gap / after reconnect). */
    fun refreshCloudRegistry() {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        bootstrapScope.launch {
            runCatching { refreshCloudRegistryLocked() }
                .onFailure { error ->
                    println("GoogleLinkCoordinator: cloud registry refresh failed - ${error.message}")
                }
        }
    }

    private suspend fun refreshCloudRegistryLocked() {
        if (!cloudOpsActive) {
            restoreSessionAndListen()
            return
        }
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        val selfId = loadLocalIdentity().deviceId
        val epoch = sessionEpoch
        val records = CloudAuthBackend.fetchAllUserDevices(uid)
        applyRemoteDevices(records, selfId, epoch)
    }

    fun linkedPeerFcmTargets(selfDeviceId: String): List<FcmWakeTarget> =
        cachedCloudRecords.mapNotNull { it.toFcmTargetOrNull(selfDeviceId) }

    suspend fun publishClipboardPublicKey(publicKeyBase64: String) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return
        val trimmed = publicKeyBase64.trim()
        if (trimmed.isEmpty()) return
        runCatching {
            CloudAuthBackend.patchDeviceClipboardPublicKey(uid, loadLocalIdentity().deviceId, trimmed)
        }.onFailure { error ->
            println("GoogleLinkCoordinator: clipboard public key patch failed - ${error.message}")
        }
    }

    /**
     * Drive-relay FCM targets for [deviceIds]. Cache first, then a live Firestore read.
     * A token is enough — do not drop Honor/other Android rows whose platform string is not
     * exactly "Android". If the paired ID still has no token, wake every linked token;
     * retrieve stays scoped by ledger target.
     */
    suspend fun fcmTargetsForDevices(
        selfDeviceId: String,
        deviceIds: List<String>
    ): List<FcmWakeTarget> {
        val wanted = deviceIds.filter { it.isNotBlank() && it != selfDeviceId }.distinct()
        if (wanted.isEmpty()) {
            return linkedPeerFcmTargets(selfDeviceId).ifEmpty { fetchAllFcmTargets(selfDeviceId) }
        }
        val cached = linkedPeerFcmTargets(selfDeviceId).filter { it.deviceId in wanted }
        val missing = wanted.filter { id -> cached.none { it.deviceId == id } }
        if (missing.isEmpty()) return cached
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return cached.ifEmpty { linkedPeerFcmTargets(selfDeviceId) }
        val fetched = missing.mapNotNull { deviceId ->
            runCatching { CloudAuthBackend.fetchCloudDeviceRecord(uid, deviceId) }.getOrNull()
                ?.toFcmTargetOrNull(selfDeviceId)
        }
        val found = (cached + fetched).distinctBy { it.deviceId }
        if (found.size == wanted.size) return found
        val all = fetchAllFcmTargets(selfDeviceId)
        val matched = all.filter { it.deviceId in wanted }
        return matched.ifEmpty { (found + all).distinctBy { it.deviceId } }
    }

    private suspend fun fetchAllFcmTargets(selfDeviceId: String): List<FcmWakeTarget> {
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return emptyList()
        val records = runCatching { CloudAuthBackend.fetchAllUserDevices(uid) }.getOrDefault(emptyList())
        if (records.isNotEmpty()) cachedCloudRecords = records
        return records.mapNotNull { it.toFcmTargetOrNull(selfDeviceId) }
    }

    private fun CloudDeviceRecord.toFcmTargetOrNull(selfDeviceId: String): FcmWakeTarget? {
        if (deviceId.isBlank() || deviceId == selfDeviceId) return null
        if (fcmToken.isBlank()) return null
        return FcmWakeTarget(deviceId, fcmToken)
    }

    /**
     * Registers/refreshes this device's FCM token in Firestore when cloud-linked.
     * @return true when the token was written.
     */
    suspend fun patchSelfFcmToken(fcmToken: String): Boolean {
        val trimmed = fcmToken.trim()
        if (trimmed.isEmpty()) return false
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return false
        if (!cloudOpsActive) return false
        val uid = FileApexServices.settings.googleAccountUid.value
        if (uid.isBlank()) return false
        return runCatching {
            CloudAuthBackend.patchDeviceFcmToken(uid, loadLocalIdentity().deviceId, trimmed)
            true
        }.getOrElse { error ->
            println("GoogleLinkCoordinator: FCM token patch failed - ${error.message}")
            false
        }
    }

    /**
     * Complete link after platform OAuth / Credential Manager yields a Google ID token.
     * Android also stores a restore key for zero-tap sign-in on a new device.
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
            RestoreCredentials.markProbedThisInstall()
            RestoreCredentials.createForSignedInUser(session.firebaseUid, email)
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
                runCatching {
                    DiagnosticsCloudRelay.syncCloudOptIn(uid, deviceId, enabled = false)
                }
                runCatching { CloudAuthBackend.deleteDevice(uid, deviceId) }
            }
            runCatching { CloudAuthBackend.signOut() }
            runCatching { RestoreCredentials.clear() }
            FileApexServices.settings.setGoogleAccountEmail("")
            FileApexServices.settings.setGoogleAccountUid("")
            FileApexServices.settings.setGoogleAccountLinkEnabled(false)
            com.fileapex.cloud.drive.DriveRelayCoordinator.clearGrantOnUnlink()
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
                println("GoogleLinkCoordinator: cloud remove failed - ${error.message}")
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
        require(trimmed.isNotEmpty()) { AppI18n.t("device_name_empty") }
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

    private suspend fun restoreSessionZeroTap() {
        val existing = CloudAuthBackend.currentSession()
        if (existing != null) {
            restoreSessionAndListen()
            val email = existing.email.ifBlank {
                FileApexServices.settings.googleAccountEmail.value
            }
            RestoreCredentials.markProbedThisInstall()
            RestoreCredentials.createForSignedInUser(existing.firebaseUid, email)
            return
        }
        val settings = FileApexServices.settings
        val linked = settings.googleAccountLinkEnabled.value
        val restoredIdToken: Boolean
        if (GoogleLinkRestorePolicy.shouldProbeRestoreKey(
                linkedFlag = linked,
                alreadyProbedThisInstall = RestoreCredentials.alreadyProbedThisInstall()
            )
        ) {
            val restored = RestoreCredentials.restoreGoogleIdToken()
            RestoreCredentials.markProbedThisInstall()
            if (restored != null) {
                linkWithGoogleIdToken(restored.first, restored.second)
                return
            }
            restoredIdToken = false
        } else {
            restoredIdToken = false
        }
        if (GoogleLinkRestorePolicy.shouldClearLinkedFlag(
                linkedFlag = linked,
                hasFirebaseSession = false,
                restoredIdToken = restoredIdToken
            )
        ) {
            println("GoogleLinkCoordinator: linked flag without session or restore key - signing out")
            settings.setGoogleAccountLinkEnabled(false)
        }
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
        cachedCloudRecords = emptyList()

        val previousHandle = registryHandle
        registryHandle = null
        previousHandle?.stop()
        previousHandle?.awaitIdle()

        DiagnosticsCloudRelay.stopInbox()

        val previousJob = sessionJob
        sessionJob = SupervisorJob()
        sessionScope = CoroutineScope(sessionJob + Dispatchers.Default)
        previousJob.cancelAndJoin()

        // Brief settle so native Firebase/SQLite mutexes are not reused mid-destroy.
        delay(SESSION_SETTLE_MS)
    }

    private fun startCloudSessionLocked(uid: String) {
        cloudOpsActive = true
        FcmTokenRegistrar.start()
        val epoch = sessionEpoch
        val selfId = loadLocalIdentity().deviceId
        val scope = sessionScope

        scope.launch {
            if (!isSessionLive(epoch)) return@launch
            runCatching {
                val key = com.fileapex.domain.clipboard.ClipboardE2ee.publicKeyBase64()
                CloudAuthBackend.patchDeviceClipboardPublicKey(uid, selfId, key)
            }.onFailure { error ->
                println("GoogleLinkCoordinator: clipboard key publish failed - ${error.message}")
            }
        }

        scope.launch {
            if (!isSessionLive(epoch)) return@launch
            runCatching {
                reconcileAndSyncDevices(uid)
                FileApexServices.deviceRepositoryOrNull()?.reconcileDuplicateEndpoints()
                val records = CloudAuthBackend.fetchAllUserDevices(uid)
                applyRemoteDevices(records, selfId, epoch)
            }.onFailure { error ->
                println("GoogleLinkCoordinator: reconcile failed - ${error.message}")
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
                    println("GoogleLinkCoordinator: observe error - ${error.message}")
                }
            }
        )

        scope.launch {
            if (!isSessionLive(epoch)) return@launch
            refreshDiagnosticsCloudRelay(uid, selfId)
        }
    }

    /**
     * Fingerprint match first, then one-time deviceName fallback for legacy docs.
     * Keep the most recently active record, write it under local deviceId, delete dupes.
     * Inactive docs older than 14 days are removed (except self).
     */
    suspend fun reconcileAndSyncDevices(uid: String) {
        if (uid.isBlank()) return
        val selfId = loadLocalIdentity().deviceId
        val selfFingerprint = com.fileapex.platform.localHardwareFingerprint()
        val selfName = LocalDeviceNameStore.current().ifBlank { loadLocalIdentity().deviceName }
        val now = TimeUtils.now()
        val fourteenDaysMs = 14L * 24 * 3600 * 1000L
        val staleCutoffMs = now - fourteenDaysMs

        runCatching {
            val allRecords = CloudAuthBackend.fetchAllUserDevices(uid)
            if (allRecords.isEmpty()) {
                registerSelf(uid)
                return
            }

            val locallyPairedIds = FileApexServices.deviceRepositoryOrNull()
                ?.listDevices()
                ?.map { it.deviceId }
                ?.toSet()
                .orEmpty()

            val activeRecords = mutableListOf<CloudDeviceRecord>()
            for (record in allRecords) {
                val isStale = record.updatedAtEpochMs > 0L && record.updatedAtEpochMs < staleCutoffMs
                val keepForLocalRoster = record.deviceId in locallyPairedIds
                if (isStale && record.deviceId != selfId && !keepForLocalRoster) {
                    println("GoogleLinkCoordinator: Pruning 14-day stale device doc ${record.deviceId} (${record.deviceName})")
                    CloudAuthBackend.deleteDevice(uid, record.deviceId)
                } else {
                    activeRecords.add(record)
                }
            }

            val primaryMatches = activeRecords.filter { record ->
                val fp = record.hardwareFingerprint
                fp.isNotEmpty() &&
                    fp["manufacturer"] == selfFingerprint["manufacturer"] &&
                    fp["model"] == selfFingerprint["model"] &&
                    fp["device"] == selfFingerprint["device"] &&
                    fp["board"] == selfFingerprint["board"]
            }

            val fallbackMatches = if (primaryMatches.isEmpty()) {
                activeRecords.filter { record ->
                    record.deviceName.isNotBlank() &&
                        record.deviceName.equals(selfName.trim(), ignoreCase = true)
                }
            } else {
                emptyList()
            }

            val matchingRecords = if (primaryMatches.isNotEmpty()) primaryMatches else fallbackMatches

            if (matchingRecords.isNotEmpty()) {
                val sortedMatches = matchingRecords.sortedByDescending { it.updatedAtEpochMs }
                val bestMatch = sortedMatches.first()

                val duplicates = activeRecords.filter { record ->
                    record.deviceId != selfId &&
                        (matchingRecords.any { it.deviceId == record.deviceId } ||
                            (record.deviceName.isNotBlank() && record.deviceName.equals(selfName.trim(), ignoreCase = true)))
                }.filter { it.deviceId != bestMatch.deviceId }

                for (duplicate in duplicates) {
                    println("GoogleLinkCoordinator: Deleting duplicate document ${duplicate.deviceId} (${duplicate.deviceName})")
                    CloudAuthBackend.deleteDevice(uid, duplicate.deviceId)
                }

                if (bestMatch.deviceId != selfId) {
                    println("GoogleLinkCoordinator: Migrating old doc ${bestMatch.deviceId} -> $selfId")
                    CloudAuthBackend.deleteDevice(uid, bestMatch.deviceId)
                }

                val consolidatedRecord = buildSelfRecord().copy(
                    deviceName = bestMatch.deviceName.ifBlank { selfName }
                )
                CloudAuthBackend.registerDevice(uid, consolidatedRecord)
                lastPublishedPresence = buildSelfPresence().copy(
                    updatedAtEpochMs = consolidatedRecord.updatedAtEpochMs
                )
            } else {
                // First-time registration
                registerSelf(uid)
            }
        }.onFailure { error ->
            println("GoogleLinkCoordinator: reconcileAndSyncDevices error - ${error.message}")
        }
    }

    /** Sync diagnostics relay opt-in + inbox when cloud session is active. */
    suspend fun refreshDiagnosticsCloudRelay(uid: String, selfDeviceId: String? = null) {
        if (!FileApexServices.settings.googleAccountLinkEnabled.value) return
        if (!cloudOpsActive) return
        if (uid.isBlank()) return
        val deviceId = selfDeviceId ?: loadLocalIdentity().deviceId
        val enabled = FileApexServices.settings.deviceDetailsAllowOverCellular.value
        runCatching {
            DiagnosticsCloudRelay.syncCloudOptIn(uid, deviceId, enabled)
        }.onFailure { error ->
            println("GoogleLinkCoordinator: diagnostics cloud sync failed - ${error.message}")
        }
        if (enabled) {
            DiagnosticsCloudRelay.startInbox(uid, deviceId)
        } else {
            DiagnosticsCloudRelay.stopInbox()
        }
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
            updatedAtEpochMs = presence.updatedAtEpochMs,
            hardwareFingerprint = presence.hardwareFingerprint
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
            ),
            hardwareFingerprint = com.fileapex.platform.localHardwareFingerprint()
        )
    }

    /**
     * Remote snapshot seeds peers into Room. Never writes Firestore from here.
     *
     * This device's display name is owned locally after a rename. A factory/hardware
     * name may still be replaced by a cloud custom name so an APK update cannot
     * lose a rename that never landed in local prefs.
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
                        adoptCloudNameIfLocalIsFactory(remote.deviceName)
                        return@forEach
                    }
                    val local = repo.getDevice(remote.deviceId)
                    val mergedIp = remote.lastKnownIp.trim().ifBlank { local?.lastKnownIp.orEmpty() }
                    val mergedPort = remote.port.takeIf { it > 0 } ?: local?.port ?: 0
                    val fingerprintMake = remote.hardwareFingerprint["manufacturer"].orEmpty()
                    val fingerprintModel = remote.hardwareFingerprint["model"].orEmpty()
                    runCatching {
                        if (!isSessionLive(epoch)) return@runCatching
                        repo.reinstateFromCloudSeed(
                            PairedDeviceEntity(
                                deviceId = remote.deviceId,
                                deviceName = remote.deviceName.ifBlank { "Cloud device" },
                                lastKnownIp = mergedIp,
                                port = mergedPort,
                                publicKeyHash = remote.publicKeyHash,
                                publicKey = remote.clipboardPublicKey.trim().ifBlank {
                                    local?.publicKey.orEmpty()
                                },
                                e2eeEnabled = remote.clipboardPublicKey.isNotBlank() ||
                                    local?.publicKey?.isNotBlank() == true,
                                rootPath = remote.rootPath.ifBlank { "/" },
                                clientVersion = remote.clientVersion.ifBlank {
                                    local?.clientVersion.orEmpty()
                                },
                                clientVersionCode = remote.clientVersionCode.takeIf { it > 0 }
                                    ?: local?.clientVersionCode
                                    ?: 0,
                                platform = remote.platform.ifBlank { local?.platform.orEmpty() },
                                deviceMake = fingerprintMake.ifBlank { local?.deviceMake.orEmpty() },
                                deviceModel = fingerprintModel.ifBlank { local?.deviceModel.orEmpty() },
                                lastSeenEpochMs = remote.updatedAtEpochMs.coerceAtLeast(0L)
                            )
                        )
                        val hasLanEndpoint = mergedIp.isNotEmpty() &&
                            mergedIp != "127.0.0.1" &&
                            mergedPort > 0
                        if (hasLanEndpoint &&
                            remote.updatedAtEpochMs > 0L &&
                            FileApexServices.isDatabaseReady()
                        ) {
                            FileApexServices.presenceMonitor.notifyPassiveReachability(
                                remote.deviceId,
                                epochMs = remote.updatedAtEpochMs
                            )
                        }
                    }.onFailure { error ->
                        println(
                            "GoogleLinkCoordinator: skip Room upsert after teardown - ${error.message}"
                        )
                    }
                }
            runCatching { repo.reconcileDuplicateEndpoints() }
        }
    }

    private suspend fun adoptCloudNameIfLocalIsFactory(cloudName: String) {
        val trimmed = cloudName.trim()
        if (trimmed.isEmpty()) return
        val hardware = localDeviceHardwareProfile()
        val current = LocalDeviceNameStore.current().ifBlank { loadLocalIdentity().deviceName }
        if (!DeviceDisplayNames.isFactory(current, hardware.deviceMake, hardware.deviceModel)) return
        if (DeviceDisplayNames.isFactory(trimmed, hardware.deviceMake, hardware.deviceModel)) return
        LocalDeviceNameStore.apply(trimmed)
        runCatching { FileApexServices.pairingCoordinator.broadcastSelfIdentity() }
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
