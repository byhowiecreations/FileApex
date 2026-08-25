package com.fileapex.domain.clipboard

import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.platform.BriefToast
import com.fileapex.platform.ClipboardChangeMonitor
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.platform.isWebUrl
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ClipboardShareCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    private var started = false

    private var pending: PendingShare? = null
    private var retryJob: Job? = null
    private var monitorJob: Job? = null
    private var initJob: Job? = null

    fun ensureStarted() {
        if (started) return
        started = true
        ClipboardPushDeduper.beginInitialization()
        monitorJob?.cancel()
        monitorJob = scope.launch {
            val settings = FileApexServices.settings
            combine(
                settings.clipboardSharingEnabled,
                settings.clipboardAccessibilityEnabled,
                settings.clipboardAutoSendEnabled
            ) { _, _, _ -> shouldWatchLocalClipboard() }.collect { watch ->
                if (watch) {
                    ClipboardChangeMonitor.start(::onLocalClipboardChanged)
                    publishClipboardPublicKey()
                } else {
                    ClipboardChangeMonitor.stop()
                    if (!settings.clipboardSharingEnabled.value) dropPending()
                }
            }
        }
        if (FileApexServices.settings.clipboardSharingEnabled.value) {
            scope.launch { publishClipboardPublicKey() }
        }
        if (shouldWatchLocalClipboard()) {
            ClipboardChangeMonitor.start(::onLocalClipboardChanged)
        }
        initJob?.cancel()
        initJob = scope.launch {
            delay(ClipboardSharePolicy.INIT_GUARD_MS)
            ClipboardPushDeduper.endInitialization()
        }
    }

    fun onLocalClipboardChanged(text: String) {
        if (!shouldWatchLocalClipboard()) return
        val trimmed = preparedAutomaticText(text) ?: return
        if (!ClipboardPushDeduper.shouldAllowAutomaticPush(trimmed)) return
        if (!FileApexServices.settings.clipboardSharingEnabled.value) return
        if (FileApexServices.settings.clipboardShareMode.value == ClipboardShareMode.UNSET) {
            showClipboardToast(com.fileapex.i18n.AppI18n.t("choose_all_or_specific_toast"))
            return
        }
        val android = currentPlatformLabel() == "Android"
        scope.launch {
            captureAndBroadcast(trimmed, desktopPeersOnly = android)
        }
        showClipboardToast(com.fileapex.i18n.AppI18n.t("sending_clipboard"))
    }

    @Volatile
    private var pendingAndroidFocusPush = false

    fun onAppForegrounded() {
        if (!FileApexServices.settings.clipboardSharingEnabled.value) return
        ClipboardChangeMonitor.onAppForegrounded()
        if (ClipboardPushDeduper.isInitializing) {
            ClipboardPushDeduper.endInitialization()
            initJob?.cancel()
            return
        }
        if (currentPlatformLabel() == "Android") {
            pendingAndroidFocusPush = true
            return
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        ClipboardChangeMonitor.onWindowFocusChanged(hasFocus)
        if (!hasFocus || currentPlatformLabel() != "Android") return
        if (!pendingAndroidFocusPush) return
        pendingAndroidFocusPush = false
        scope.launch {
            ClipboardSharePolicy.ANDROID_FOCUS_CLIP_RETRY_MS.forEach { delayMs ->
                delay(delayMs)
                pushCurrentClipboard()
            }
        }
    }

    fun onAppBackgrounded() {
        pendingAndroidFocusPush = false
        ClipboardChangeMonitor.onAppBackgrounded()
    }

    fun pushCurrentClipboard() {
        if (!FileApexServices.settings.clipboardSharingEnabled.value) return
        if (currentPlatformLabel() != "Android" &&
            !FileApexServices.settings.clipboardAutoSendEnabled.value
        ) return
        if (FileApexServices.settings.clipboardShareMode.value == ClipboardShareMode.UNSET) return
        val text = preparedAutomaticText(PlatformClipboard.getSystemClipboardText()) ?: return
        if (!ClipboardPushDeduper.shouldAllowAutomaticPush(text)) return
        scope.launch {
            captureAndBroadcast(
                text,
                desktopPeersOnly = currentPlatformLabel() == "Android"
            )
        }
    }

    suspend fun pushCurrentClipboardNow(prefetchedText: String? = null): String {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            return com.fileapex.i18n.AppI18n.t("clipboard_sharing_off")
        }
        if (settings.clipboardShareMode.value == ClipboardShareMode.UNSET) {
            return com.fileapex.i18n.AppI18n.t("choose_devices_first")
        }
        val raw = prefetchedText?.trim()?.takeIf { it.isNotBlank() }
            ?: withContext(Dispatchers.Main.immediate) {
                PlatformClipboard.getSystemClipboardText()?.takeIf { it.isNotBlank() }
            }
        val text = preparedManualText(raw)
            ?: return preparedManualError(raw)
        if (!ClipboardPushDeduper.shouldAllowManualPush(text)) {
            return com.fileapex.i18n.AppI18n.t("already_sent")
        }
        ClipboardPushDeduper.remember(text)
        captureAndBroadcast(
            text,
            desktopPeersOnly = currentPlatformLabel() == "Android"
        )
        return com.fileapex.i18n.AppI18n.t("sending_clipboard")
    }

    suspend fun sendToDevice(deviceId: String): ClipboardSendResponse {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            error(com.fileapex.i18n.AppI18n.t("clipboard_sharing_disabled_settings"))
        }
        val raw = PlatformClipboard.getSystemClipboardText()
        val text = preparedManualText(raw) ?: error(preparedManualError(raw))
        val device = FileApexServices.deviceRepository.getDevice(deviceId)
            ?: error(com.fileapex.i18n.AppI18n.t("device_not_found"))
        val capturedAt = TimeUtils.now()
        val delivered = deliverToDevice(device, text, capturedAt)
        if (!delivered) {
            error(com.fileapex.i18n.AppI18n.t("clipboard_send_failed", device.deviceName))
        }
        ClipboardPushDeduper.remember(text)
        val name = device.deviceName.ifBlank { com.fileapex.i18n.AppI18n.t("paired_device") }
        return ClipboardSendResponse(status = "ok", recipientDeviceName = name)
    }

    suspend fun sendPlaintextToDevice(deviceId: String, text: String): ClipboardSendResponse {
        val trimmed = preparedManualText(text)
            ?: error(preparedManualError(text))
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            error(com.fileapex.i18n.AppI18n.t("clipboard_sharing_disabled_settings"))
        }
        val device = FileApexServices.deviceRepository.getDevice(deviceId)
            ?: error(com.fileapex.i18n.AppI18n.t("device_not_found"))
        val capturedAt = TimeUtils.now()
        val delivered = deliverToDevice(device, trimmed, capturedAt)
        if (!delivered) {
            error(com.fileapex.i18n.AppI18n.t("clipboard_send_failed", device.deviceName))
        }
        ClipboardPushDeduper.remember(trimmed)
        val name = device.deviceName.ifBlank { com.fileapex.i18n.AppI18n.t("paired_device") }
        return ClipboardSendResponse(status = "ok", recipientDeviceName = name)
    }

    suspend fun applyInbound(
        senderDeviceId: String,
        senderDeviceName: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: Long
    ) {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) {
            error("clipboard_disabled")
        }
        if (ciphertext.isBlank() || senderPublicKey.isBlank() || senderDeviceId.isBlank()) {
            error("clipboard_ciphertext_required")
        }
        if (ClipboardSharePolicy.isExpired(capturedAtEpochMs, TimeUtils.now())) {
            error("clipboard_expired")
        }
        val localId = loadLocalIdentity().deviceId
        val plaintext = ClipboardE2ee.decrypt(
            ciphertextBase64 = ciphertext,
            localDeviceId = localId,
            peerDeviceId = senderDeviceId,
            peerPublicKeyBase64 = senderPublicKey
        ).decodeToString()
        val prepared = ClipboardCopySignals.prepare(plaintext)
        if (prepared !is ClipboardCopySignals.Prepared.Ok) {
            error("empty_text")
        }
        ClipboardPushDeduper.remember(prepared.text)
        withContext(Dispatchers.Main) {
            PlatformClipboard.applyRemoteText(prepared.text)
            if (isWebUrl(prepared.text)) {
                PlatformClipboard.openUrlInDefaultBrowser(prepared.text)
            }
        }
    }

    suspend fun applyFcmInbound(
        senderDeviceId: String,
        senderDeviceName: String,
        senderPublicKey: String,
        ciphertext: String,
        capturedAtEpochMs: String?
    ) {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) return
        if (!settings.clipboardViaCellularEnabled.value) return
        if (currentPlatformLabel() != "Android") return
        val capturedAt = capturedAtEpochMs?.toLongOrNull() ?: 0L
        runCatching {
            applyInbound(
                senderDeviceId = senderDeviceId,
                senderDeviceName = senderDeviceName,
                senderPublicKey = senderPublicKey,
                ciphertext = ciphertext,
                capturedAtEpochMs = capturedAt
            )
        }.onFailure { error ->
            println("ClipboardShareCoordinator: FCM clipboard dropped - ${error.message}")
        }
    }

    private suspend fun captureAndBroadcast(text: String, desktopPeersOnly: Boolean) {
        mutex.withLock {
            val settings = FileApexServices.settings
            val paired = FileApexServices.deviceRepository.listDevices()
            val targets = ClipboardSharePolicy.resolveBroadcastTargets(
                mode = settings.clipboardShareMode.value,
                peers = paired.map {
                    ClipboardSharePolicy.PeerRef(
                        deviceId = it.deviceId,
                        isDesktop = PeerPlatform.isDesktop(it.os, it.platform)
                    )
                },
                selectedDeviceIds = settings.clipboardTargetDeviceIds.value,
                desktopPeersOnly = desktopPeersOnly
            )
            if (targets.isEmpty()) {
                pending = null
                return@withLock
            }
            ClipboardPushDeduper.remember(text)
            pending = PendingShare(
                text = text,
                capturedAtEpochMs = TimeUtils.now(),
                remainingIds = targets.toMutableSet()
            )
        }
        if (mutex.withLock { pending } == null) return
        attemptPending()
        scheduleRetries()
    }

    private fun scheduleRetries() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (true) {
                delay(ClipboardSharePolicy.RETRY_INTERVAL_MS)
                val live = mutex.withLock { pending }
                    ?: break
                if (ClipboardSharePolicy.isExpired(live.capturedAtEpochMs, TimeUtils.now())) {
                    dropPending()
                    break
                }
                if (live.remainingIds.isEmpty()) break
                attemptPending()
            }
        }
    }

    private suspend fun attemptPending() {
        val snapshot = mutex.withLock { pending } ?: return
        if (ClipboardSharePolicy.isExpired(snapshot.capturedAtEpochMs, TimeUtils.now())) {
            dropPending()
            return
        }
        val remaining = snapshot.remainingIds.toList()
        if (remaining.isEmpty()) {
            dropPending()
            return
        }
        for (deviceId in remaining) {
            val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: continue
            val delivered = runCatching {
                deliverToDevice(device, snapshot.text, snapshot.capturedAtEpochMs)
            }.getOrDefault(false)
            if (delivered) {
                mutex.withLock {
                    pending?.remainingIds?.remove(deviceId)
                    if (pending?.remainingIds?.isEmpty() == true) {
                        pending = null
                    }
                }
            }
        }
        mutex.withLock {
            val live = pending ?: return
            if (ClipboardSharePolicy.isExpired(live.capturedAtEpochMs, TimeUtils.now())) {
                pending = null
            }
        }
    }

    private suspend fun dropPending() {
        mutex.withLock {
            pending = null
        }
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun deliverToDevice(
        device: PairedDeviceEntity,
        text: String,
        capturedAtEpochMs: Long
    ): Boolean {
        if (ClipboardSharePolicy.isExpired(capturedAtEpochMs, TimeUtils.now())) {
            return false
        }
        val peerKey = resolvePeerPublicKey(device)
        if (peerKey.isBlank()) {
            println("ClipboardShareCoordinator: no public key for ${device.deviceName}")
            return false
        }
        val identity = loadLocalIdentity()
        val ciphertext = ClipboardE2ee.encrypt(
            plaintext = text.encodeToByteArray(),
            localDeviceId = identity.deviceId,
            peerDeviceId = device.deviceId,
            peerPublicKeyBase64 = peerKey
        )
        val lanOk = ClipboardSharePolicy.canUseLocalLan(
            lanConnected = isActiveLanConnectivity() && PeerLanHttpPolicy.canRoute(device.lastKnownIp),
            peerHost = device.lastKnownIp,
            localBindIps = NetworkUtils.lanBindCandidates()
        )
        if (lanOk) {
            val sent = runCatching {
                FileApexServices.client.sendClipboard(
                    host = device.lastKnownIp,
                    port = device.port,
                    senderDeviceId = identity.deviceId,
                    senderDeviceName = identity.deviceName,
                    senderPublicKey = ClipboardE2ee.publicKeyBase64(),
                    ciphertext = ciphertext,
                    capturedAtEpochMs = capturedAtEpochMs
                )
            }.onFailure { error ->
                println(
                    "ClipboardShareCoordinator: LAN send to ${device.deviceName} failed - ${error.message}"
                )
            }.isSuccess
            if (sent) return true
        }
        val settings = FileApexServices.settings
        val fcmOk = ClipboardSharePolicy.canUseCellularFcm(
            viaCellularEnabled = settings.clipboardViaCellularEnabled.value,
            selfIsAndroid = currentPlatformLabel() == "Android",
            peerIsAndroid = PeerPlatform.isAndroid(device.os, device.platform),
            googleLinked = settings.googleAccountLinkEnabled.value
        )
        if (!fcmOk) return false
        if (ciphertext.length > ClipboardSharePolicy.FCM_MAX_DATA_CHARS) return false
        return FcmWakeCoordinator.dispatchClipboardShare(
            targetDeviceId = device.deviceId,
            senderPublicKey = ClipboardE2ee.publicKeyBase64(),
            ciphertext = ciphertext,
            capturedAtEpochMs = capturedAtEpochMs,
            senderDeviceName = identity.deviceName
        )
    }

    private suspend fun resolvePeerPublicKey(device: PairedDeviceEntity): String {
        val stored = device.publicKey.trim()
        if (stored.isNotEmpty()) return stored
        val cloud = GoogleLinkCoordinator.cloudRecordFor(device.deviceId)
            ?.clipboardPublicKey.orEmpty().trim()
        if (cloud.isNotEmpty()) return cloud
        val live = runCatching {
            FileApexServices.client.fetchPeerNodeState(device.lastKnownIp, device.port)
        }.getOrNull() ?: return ""
        val key = live.publicKey.trim()
        if (key.isNotEmpty()) {
            runCatching {
                FileApexServices.deviceRepository.applyPeerNodeState(live, device.deviceId)
            }
        }
        return key
    }

    private fun shouldWatchLocalClipboard(): Boolean {
        val settings = FileApexServices.settings
        if (!settings.clipboardSharingEnabled.value) return false
        return if (currentPlatformLabel() == "Android") {
            true
        } else {
            settings.clipboardAutoSendEnabled.value
        }
    }

    private suspend fun publishClipboardPublicKey() {
        runCatching {
            GoogleLinkCoordinator.publishClipboardPublicKey(ClipboardE2ee.publicKeyBase64())
        }
    }

    private fun preparedAutomaticText(raw: String?): String? {
        return when (val prepared = ClipboardCopySignals.prepare(raw)) {
            is ClipboardCopySignals.Prepared.Ok -> prepared.text
            ClipboardCopySignals.Prepared.Empty -> null
            ClipboardCopySignals.Prepared.TooLarge -> {
                showClipboardToast(com.fileapex.i18n.AppI18n.t("clipboard_too_large"))
                null
            }
            ClipboardCopySignals.Prepared.NotText -> {
                showClipboardToast(com.fileapex.i18n.AppI18n.t("clipboard_not_text"))
                null
            }
        }
    }

    private fun preparedManualText(raw: String?): String? =
        (ClipboardCopySignals.prepare(raw) as? ClipboardCopySignals.Prepared.Ok)?.text

    private fun preparedManualError(raw: String?): String {
        return when (ClipboardCopySignals.prepare(raw)) {
            is ClipboardCopySignals.Prepared.Ok,
            ClipboardCopySignals.Prepared.Empty -> com.fileapex.i18n.AppI18n.t("clipboard_empty")
            ClipboardCopySignals.Prepared.TooLarge -> com.fileapex.i18n.AppI18n.t("clipboard_too_large")
            ClipboardCopySignals.Prepared.NotText -> com.fileapex.i18n.AppI18n.t("clipboard_not_text")
        }
    }

    private data class PendingShare(
        val text: String,
        val capturedAtEpochMs: Long,
        val remainingIds: MutableSet<String>
    )
}

private fun showClipboardToast(message: String) {
    val inForeground = runCatching {
        FileApexServices.presenceMonitor.isAppInForeground()
    }.getOrDefault(false)
    if (inForeground) return
    BriefToast.show(message)
}
