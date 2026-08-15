package com.fileapex.cloud.drive

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.data.note.NoteRecord
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.MultiCopySource
import com.fileapex.platform.DriveRelayNotifier
import com.fileapex.platform.UniqueFileNames
import com.fileapex.platform.defaultDownloadsDir
import com.fileapex.platform.generateDeviceId
import com.fileapex.util.TimeUtils
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Google Drive relay: upload, ledger, FCM pointer, download, pin, and 72-hour purge.
 */
object DriveRelayCoordinator {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processedHashes = mutableSetOf<String>()
    private var cachedLedger: DriveLedger? = null
    private var cachedEtag: String? = null

    private val _pendingReceivePrompt = MutableStateFlow(false)
    val pendingReceivePrompt: StateFlow<Boolean> = _pendingReceivePrompt.asStateFlow()

    private val _pendingSendPrompt = MutableStateFlow(false)
    val pendingSendPrompt: StateFlow<Boolean> = _pendingSendPrompt.asStateFlow()

    private var pendingSendSources: List<MultiCopySource> = emptyList()
    private var pendingSendDeviceIds: List<String> = emptyList()

    fun onAppLaunch() {
        installDriveGrantRuntime()
        scope.launch {
            reconcileStoredGrant()
            applySchedulerFromSettings()
            if (DriveRelayPolicy.isRelayEnabled()) {
                DriveRelayNotifier.onDriveEnabledAndGranted()
                runCatching { sweep(forceReload = true) }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        driveLogError("launch Drive sweep failed", error)
                    }
                runCatching { FileApexServices.transferQueue.scheduleDrain() }
            }
        }
    }

    private suspend fun reconcileStoredGrant() {
        if (GoogleDriveAuth.hasGrant() || !GoogleDriveAuth.hasStoredAccess()) return
        runCatching {
            GoogleDriveClient.verifyRelayAccess()
            GoogleDriveAuth.markAccessVerified()
            if (!FileApexServices.settings.googleDriveRelayEnabled.value) {
                FileApexServices.settings.setGoogleDriveRelayEnabled(true)
            }
            DriveRelayNotifier.onDriveEnabledAndGranted()
        }.onFailure { error ->
            driveLogError("stored Drive grant failed probe — keeping tokens", error)
        }
    }

    fun onLeftLocalNetwork() {
        if (DriveRelayPolicy.isRelayEnabled()) {
            DriveSyncScheduler.enqueueImmediateSweep()
        }
    }

    fun applySchedulerFromSettings() {
        if (FileApexServices.settings.cellularEnabled.value &&
            FileApexServices.settings.googleDriveRelayEnabled.value &&
            FileApexServices.settings.googleAccountLinkEnabled.value &&
            GoogleDriveAuth.hasGrant()
        ) {
            DriveSyncScheduler.ensureScheduled()
        } else {
            DriveSyncScheduler.cancel()
        }
    }

    fun onFcmRelayPointer() {
        if (DriveRelayPolicy.needsReceivePrompt()) {
            _pendingReceivePrompt.value = true
            return
        }
        if (!DriveRelayPolicy.canReceive()) {
            driveLog("FCM Drive pointer ignored — relay not ready to receive")
            return
        }
        scope.launch {
            runCatching { sweep(forceReload = true) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    driveLogError("FCM Drive retrieve failed", error)
                }
        }
    }

    fun acknowledgeReceivePrompt() {
        FileApexServices.settings.setCellularReceivePromptAcknowledged(true)
        _pendingReceivePrompt.value = false
        DriveSyncScheduler.enqueueImmediateSweep()
    }

    fun dismissReceivePrompt() {
        _pendingReceivePrompt.value = false
    }

    fun requestSendConfirmation(sources: List<MultiCopySource>, deviceIds: List<String>) {
        pendingSendSources = sources
        pendingSendDeviceIds = deviceIds
        _pendingSendPrompt.value = true
    }

    fun acknowledgeSendPrompt() {
        FileApexServices.settings.setCellularSendPromptAcknowledged(true)
        _pendingSendPrompt.value = false
        val sources = pendingSendSources
        val deviceIds = pendingSendDeviceIds
        pendingSendSources = emptyList()
        pendingSendDeviceIds = emptyList()
        if (sources.isNotEmpty() && deviceIds.isNotEmpty()) {
            scope.launch {
                runCatching { uploadDirectTransfers(sources, deviceIds) }
                    .onSuccess { entries ->
                        DriveRelayNotifier.notifyPosted(
                            fileNames = entries.map { it.fileName }.distinct(),
                            targetNames = deviceNamesForIds(deviceIds)
                        )
                    }
                    .onFailure { error ->
                        driveLogError("confirmed send failed", error)
                        runCatching {
                            FileApexServices.transferQueue.enqueueSources(sources, deviceIds)
                        }.onFailure { queueError ->
                            driveLogError("queue after Drive failure also failed", queueError)
                        }
                        DriveRelayNotifier.notifyFailed(
                            fileName = sources.firstOrNull()?.fileName.orEmpty(),
                            queued = true
                        )
                    }
            }
        }
    }

    fun dismissSendPrompt() {
        _pendingSendPrompt.value = false
        pendingSendSources = emptyList()
        pendingSendDeviceIds = emptyList()
    }

    suspend fun uploadNoteAttachment(localPath: String, displayName: String): DriveLedgerEntry {
        require(DriveRelayPolicy.canSend()) { "Cellular Google Drive Relay is not enabled" }
        val fileLen = SystemFileSystem.metadataOrNull(Path(localPath))?.size
            ?: error("Attachment file not found")
        require(fileLen <= DriveRelayPolicy.NOTES_ATTACHMENT_MAX_BYTES) {
            "Notes attachments must be under 5 MB"
        }
        val uploaded = GoogleDriveClient.uploadResumable(localPath, uniqueRemoteName(displayName))
        val selfId = loadLocalIdentity().deviceId
        val entry = DriveLedgerEntry(
            entryId = generateDeviceId(),
            uploadedAtEpochMs = TimeUtils.now(),
            sourceDeviceId = selfId,
            driveFileId = uploaded.id,
            contentHash = uploaded.contentHash,
            fileName = displayName,
            sizeBytes = uploaded.sizeBytes,
            targetScope = DriveLedgerScope.BROADCAST,
            kind = DriveLedgerKinds.NOTE_ATTACHMENT,
            retrievedBy = listOf(selfId),
            delivery = listOf(
                DriveTargetStatus(selfId, DriveDeliveryStates.RETRIEVED, TimeUtils.now())
            ),
            pinned = false,
            relativeDestPath = displayName
        )
        appendLedger(entry)
        FcmWakeCoordinator.dispatchDriveRelayPointer(entry.entryId)
        return entry
    }

    suspend fun uploadDirectTransfers(
        sources: List<MultiCopySource>,
        targetDeviceIds: List<String>
    ): List<DriveLedgerEntry> {
        require(DriveRelayPolicy.canSend()) { "Cellular Google Drive Relay is not enabled" }
        val selfId = loadLocalIdentity().deviceId
        val created = mutableListOf<DriveLedgerEntry>()
        require(sources.isNotEmpty()) { "Nothing to send via Google Drive Relay" }
        for (source in sources) {
            require(source.absolutePath.isNotBlank()) {
                "Drive relay source path is missing for ${source.fileName}"
            }
            driveLog("upload start name=${source.fileName} bytesPath=${source.absolutePath}")
            val uploaded = GoogleDriveClient.uploadResumable(
                source.absolutePath,
                uniqueRemoteName(source.fileName)
            )
            driveLog("upload ok id=${uploaded.id} bytes=${uploaded.sizeBytes} name=${source.fileName}")
            for (targetId in targetDeviceIds) {
                val now = TimeUtils.now()
                val entry = DriveLedgerEntry(
                    entryId = generateDeviceId(),
                    uploadedAtEpochMs = now,
                    sourceDeviceId = selfId,
                    driveFileId = uploaded.id,
                    contentHash = uploaded.contentHash,
                    fileName = source.fileName,
                    sizeBytes = uploaded.sizeBytes,
                    targetScope = targetId,
                    kind = DriveLedgerKinds.FILE_TRANSFER,
                    retrievedBy = emptyList(),
                    delivery = listOf(
                        DriveTargetStatus(targetId, DriveDeliveryStates.PENDING_SYNC, now)
                    ),
                    pinned = false,
                    relativeDestPath = source.relativeDestPath.ifBlank { source.fileName }
                )
                appendLedger(entry)
                created += entry
            }
        }
        if (created.isEmpty()) {
            error("Drive relay did not upload any files")
        }
        FcmWakeCoordinator.dispatchDriveRelayPointer(
            entryId = created.first().entryId,
            targetDeviceIds = targetDeviceIds
        )
        return created
    }

    suspend fun setNoteAttachmentPinned(driveFileId: String, pinned: Boolean) {
        mutex.withLock {
            mutateLedger { ledger ->
                ledger.copy(
                    entries = ledger.entries.map { entry ->
                        if (entry.driveFileId == driveFileId &&
                            entry.kind == DriveLedgerKinds.NOTE_ATTACHMENT
                        ) {
                            entry.copy(pinned = pinned)
                        } else {
                            entry
                        }
                    }
                )
            }
        }
    }

    suspend fun purgeRelayNow(): Int {
        mutex.withLock {
            val deleted = GoogleDriveClient.purgeRelayFolder()
            cachedLedger = DriveLedger()
            cachedEtag = null
            processedHashes.clear()
            driveLog("purged $deleted Drive relay file(s)")
            return deleted
        }
    }

    suspend fun sweep(forceReload: Boolean = false) {
        if (!DriveRelayPolicy.canReceive() && !DriveRelayPolicy.isRelayEnabled()) {
            driveLog("sweep skipped — relay not enabled")
            return
        }
        if (!GoogleDriveAuth.hasGrant()) {
            driveLog("sweep skipped — no Drive grant")
            return
        }
        mutex.withLock {
            driveLog("sweep start forceReload=$forceReload")
            val snapshot = runCatching {
                val etagHint = cachedEtag.takeIf { !forceReload && cachedLedger != null }
                GoogleDriveClient.loadLedger(etagHint)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                driveLogError("ledger load failed", error)
                return
            }
            val loaded = if (snapshot.notModified) {
                cachedLedger ?: return
            } else {
                cachedLedger = snapshot.ledger
                cachedEtag = snapshot.etag
                snapshot.ledger
            }
            val selfId = loadLocalIdentity().deviceId
            var ledger = loaded
            var dirty = false
            val now = TimeUtils.now()
            val purgeEnabled = FileApexServices.settings.drivePurgeAfter72Hours.value
            val inbound = ledger.entries.filter { entry ->
                entry.sourceDeviceId != selfId &&
                    (entry.targetScope == DriveLedgerScope.BROADCAST || entry.targetScope == selfId) &&
                    !entry.isRetrievedBy(selfId)
            }
            driveLog(
                "ledger entries=${ledger.entries.size} inbound=${inbound.size} self=$selfId"
            )
            if (DriveRelayPolicy.needsReceivePrompt() && inbound.isNotEmpty()) {
                _pendingReceivePrompt.value = true
                return
            }
            if (DriveRelayPolicy.canReceive()) {
                if (inbound.isNotEmpty()) {
                    driveLog("retrieving ${inbound.size} Drive file(s)")
                }
                val retrievedNames = mutableListOf<String>()
                for (entry in inbound) {
                    val key = "${entry.entryId}:${selfId}"
                    if (key in processedHashes) continue
                    val ok = retrieveWithRetries(entry)
                    if (ok) {
                        processedHashes += key
                        retrievedNames += entry.fileName
                        ledger = ledger.copy(
                            entries = ledger.entries.map { current ->
                                if (current.entryId == entry.entryId) {
                                    current.markRetrieved(selfId, now)
                                } else {
                                    current
                                }
                            }
                        )
                        dirty = true
                    }
                }
                if (retrievedNames.isNotEmpty()) {
                    DriveRelayNotifier.notifyRetrieved(retrievedNames)
                }
            }
            val retain = mutableListOf<DriveLedgerEntry>()
            for (entry in ledger.entries) {
                val expired = purgeEnabled &&
                    !entry.pinned &&
                    now - entry.uploadedAtEpochMs >= DriveRelayPolicy.PURGE_AFTER_MS
                if (expired || entry.isDirectTransferComplete()) {
                    runCatching { GoogleDriveClient.deleteFile(entry.driveFileId) }
                    dirty = true
                } else {
                    retain += entry
                }
            }
            if (retain.size != ledger.entries.size) {
                ledger = ledger.copy(entries = retain)
                dirty = true
            }
            if (dirty) {
                runCatching {
                    val saved = GoogleDriveClient.saveLedger(ledger, snapshot.etag ?: cachedEtag)
                    cachedLedger = saved.ledger
                    cachedEtag = saved.etag
                }.onFailure { error ->
                    driveLogError("ledger save failed", error)
                }
            }
        }
    }

    fun clearGrantOnUnlink() {
        GoogleDriveAuth.clearGrant()
        cachedLedger = null
        cachedEtag = null
        DriveSyncScheduler.cancel()
        _pendingReceivePrompt.value = false
    }

    private suspend fun retrieveWithRetries(entry: DriveLedgerEntry): Boolean {
        var attempt = 0
        while (attempt < 3) {
            val ok = runCatching { retrieveEntry(entry) }.getOrElse { error ->
                if (error is CancellationException) throw error
                driveLogError("retrieve ${entry.entryId} attempt ${attempt + 1} failed", error)
                false
            }
            if (ok) return true
            attempt += 1
        }
        return false
    }

    private suspend fun retrieveEntry(entry: DriveLedgerEntry): Boolean {
        val destRoot = defaultDownloadsDir()
        val relative = entry.relativeDestPath.ifBlank { entry.fileName }
        val preferred = "${destRoot.trimEnd('/', '\\')}/${relative.trimStart('/', '\\')}"
        val preferredPath = Path(preferred)
        val existingSize = SystemFileSystem.metadataOrNull(preferredPath)?.size
        if (SystemFileSystem.exists(preferredPath) &&
            entry.sizeBytes > 0L &&
            existingSize == entry.sizeBytes
        ) {
            driveLog("retrieve already on disk ${entry.fileName} bytes=$existingSize")
            bindRetrievedNote(entry, preferred)
            return true
        }
        val destPath = if (SystemFileSystem.exists(preferredPath)) {
            UniqueFileNames.resolve(preferred)
        } else {
            preferred
        }
        val partPath = "$destPath.part"
        driveLog("retrieve ${entry.fileName} bytes=${entry.sizeBytes} dest=$destPath")
        GoogleDriveClient.downloadToPath(entry.driveFileId, partPath, entry.sizeBytes)
        val part = Path(partPath)
        if (!SystemFileSystem.exists(part)) return false
        val have = SystemFileSystem.metadataOrNull(part)?.size
        if (entry.sizeBytes > 0L && have != null && have != entry.sizeBytes) {
            driveLog("retrieve incomplete have=$have expected=${entry.sizeBytes}")
            return false
        }
        val dest = Path(destPath)
        if (SystemFileSystem.exists(dest)) {
            SystemFileSystem.delete(dest)
        }
        SystemFileSystem.atomicMove(part, dest)
        bindRetrievedNote(entry, destPath)
        return SystemFileSystem.exists(Path(destPath))
    }

    private suspend fun bindRetrievedNote(entry: DriveLedgerEntry, destPath: String) {
        if (entry.kind != DriveLedgerKinds.NOTE_ATTACHMENT) return
        val bound = FileApexServices.noteRepository.bindDownloadedAttachment(
            driveFileId = entry.driveFileId,
            checksum = entry.contentHash,
            localPath = destPath,
            fileName = entry.fileName,
            sizeBytes = entry.sizeBytes,
            pinned = entry.pinned
        )
        if (bound) return
        val noteId = "note-drive-" + entry.entryId
        val already = FileApexServices.noteRepository.containsNoteOrChecksum(noteId, entry.contentHash)
        if (already) return
        FileApexServices.noteRepository.addNote(
            NoteRecord(
                noteId = noteId,
                sourceDeviceId = entry.sourceDeviceId,
                sourceDeviceName = "Paired Device",
                content = "",
                driveFileId = entry.driveFileId,
                checksum = entry.contentHash,
                epochMs = entry.uploadedAtEpochMs,
                isMine = false,
                attachmentFileName = entry.fileName,
                attachmentSizeBytes = entry.sizeBytes,
                attachmentPinned = entry.pinned,
                attachmentLocalPath = destPath
            )
        )
    }

    suspend fun materializeNoteAttachment(note: NoteRecord): String? {
        val driveId = note.driveFileId?.takeIf { it.isNotBlank() } ?: return null
        if (!GoogleDriveAuth.hasGrant()) return null
        val name = note.attachmentFileName?.ifBlank { null } ?: "attachment"
        val destRoot = defaultDownloadsDir()
        val preferred = "${destRoot.trimEnd('/', '\\')}/$name"
        val existing = Path(preferred)
        if (SystemFileSystem.exists(existing)) {
            val size = SystemFileSystem.metadataOrNull(existing)?.size
            if (note.attachmentSizeBytes <= 0L || size == note.attachmentSizeBytes) {
                FileApexServices.noteRepository.setAttachmentLocalPath(note.noteId, preferred)
                return preferred
            }
        }
        val destPath = if (SystemFileSystem.exists(existing)) {
            UniqueFileNames.resolve(preferred)
        } else {
            preferred
        }
        driveLog("note attachment download name=$name dest=$destPath")
        GoogleDriveClient.downloadToPath(driveId, destPath, note.attachmentSizeBytes)
        FileApexServices.noteRepository.setAttachmentLocalPath(note.noteId, destPath)
        return destPath
    }

    private suspend fun appendLedger(entry: DriveLedgerEntry) {
        mutex.withLock {
            mutateLedger { DriveLedgerCodec.upsert(it, entry) }
        }
    }

    private suspend fun mutateLedger(transform: (DriveLedger) -> DriveLedger) {
        var attempt = 0
        while (attempt < 4) {
            val snapshot = GoogleDriveClient.loadLedger()
            val next = transform(snapshot.ledger)
            val result = runCatching { GoogleDriveClient.saveLedger(next, snapshot.etag) }
            if (result.isSuccess) {
                val saved = result.getOrThrow()
                cachedLedger = saved.ledger
                cachedEtag = saved.etag
                return
            }
            val error = result.exceptionOrNull()
            if (error is DriveHttpException && error.status == 412) {
                cachedLedger = null
                cachedEtag = null
                attempt += 1
                continue
            }
            throw error ?: error("Ledger save failed")
        }
        error("Ledger save failed after retries")
    }

    private suspend fun deviceNamesForIds(deviceIds: List<String>): List<String> =
        deviceIds.map { id ->
            FileApexServices.deviceRepository.getDevice(id)?.deviceName ?: id
        }

    private fun uniqueRemoteName(displayName: String): String {
        val safe = displayName.replace('/', '_').replace('\\', '_')
        return "${TimeUtils.now()}-${generateDeviceId().take(8)}-$safe"
    }
}
