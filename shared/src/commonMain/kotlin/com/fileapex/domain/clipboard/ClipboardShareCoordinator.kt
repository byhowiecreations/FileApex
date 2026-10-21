package com.fileapex.domain.clipboard

import com.fileapex.data.db.isDockerNode
import com.fileapex.cloud.FcmWakeCoordinator
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.identity.loadLocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.domain.presence.RouteKind
import com.fileapex.domain.presence.routeKindForHost
import com.fileapex.network.PeerLanHttpPolicy
import com.fileapex.platform.BriefToast
import com.fileapex.platform.ClipboardChangeMonitor
import com.fileapex.platform.PlatformClipboard
import com.fileapex.platform.isActiveLanConnectivity
import com.fileapex.platform.isWebUrl
import com.fileapex.util.NetworkUtils
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
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
        val initialText = cancellableCatching { PlatformClipboard.getSystemClipboardText() }.getOrNull()
        if (!initialText.isNullOrBlank()) {
            ClipboardPushDeduper.remember(initialText)
        }
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
        checkAndApplyAutoDefaultTarget()
        initJob?.cancel()
        initJob = scope.launch {
            delay(ClipboardSharePolicy.INIT_GUARD_MS)
            ClipboardPushDeduper.endInitialization()
        }
    }

    fun checkAndApplyAutoDefaultTarget() {
        val settings = FileApexServices.settings
        if (settings.clipboardTargetConfigured.value) return
        scope.launch {
            // Docker backup nodes hold a pairing key for pin exchange only; they have no clipboard.
            val paired = FileApexServices.deviceRepository.listDevices().filterNot { it.isDockerNode() }
            val peers = paired.map {
                ClipboardSharePolicy.PeerRef(
                    deviceId = it.deviceId,
                    isDesktop = com.fileapex.domain.peer.PeerPlatform.isDesktop(it.os, it.platform)
                )
            }
            val defaultTargetId = ClipboardSharePolicy.resolveAutoDefaultTargetId(
                selfIsAndroid = currentPlatformLabel() == "Android",
                peers = peers
            )
            if (defaultTargetId != null) {
                if (settings.clipboardShareMode.value == ClipboardShareMode.UNSET ||
                    settings.clipboardShareMode.value == ClipboardShareMode.SPECIFIC
                ) {
                    settings.setClipboardShareMode(ClipboardShareMode.SPECIFIC)
                    settings.setClipboardTargetDeviceIds(setOf(defaultTargetId))
                }
            }
        }
    }

    fun onLocalClipboardChanged(text: String) {
        if (!shouldWatchLocalClipboard()) return
        val trimmed = preparedAutomaticText(text) ?: return
        val clipTs = PlatformClipboard.getSystemClipboardTimestamp()
        if (!ClipboardPushDeduper.shouldAllowAutomaticPush(trimmed, clipTs)) return
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
        scope.launch {
            ClipboardChangeMonitor.onAppForegrounded()
            if (ClipboardPushDeduper.isInitializing) {
                val initClip = cancellableCatching { PlatformClipboard.getSystemClipboardText() }.getOrNull()
                if (!initClip.isNullOrBlank()) {
                    ClipboardPushDeduper.remember(initClip)
                }
                ClipboardPushDeduper.endInitialization()
                initJob?.cancel()
                return@launch
            }
            if (currentPlatformLabel() == "Android") {
                pendingAndroidFocusPush = true
            }
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        ClipboardChangeMonitor.onWindowFocusChanged(hasFocus)
        if (!hasFocus || currentPlatformLabel() != "Android") return
        if (ClipboardPushDeduper.isInitializing) {
            val initClip = cancellableCatching { PlatformClipboard.getSystemClipboardText() }.getOrNull()
            if (!initClip.isNullOrBlank()) {
                ClipboardPushDeduper.remember(initClip)
            }
            ClipboardPushDeduper.endInitialization()
            initJob?.cancel()
            return
        }
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
        val clipTs = PlatformClipboard.getSystemClipboardTimestamp()
        if (!ClipboardPushDeduper.shouldAllowAutomaticPush(text, clipTs)) return
        scope.launch {
            captureAndBroadcast(
                text,
                desktopPeersOnly = currentPlatformLabel() == "Android"
            )
        }
    }

    suspend fun pushCurrentClipboardNow(prefetchedText: String? = null): String {
        checkAndApplyAutoDefaultTarget()
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
        val (started, initialError) = captureAndBroadcast(
            text,
            desktopPeersOnly = currentPlatformLabel() == "Android"
        )
        if (!started) {
            ClipboardPushDeduper.forget(text)
            return com.fileapex.i18n.AppI18n.t("choose_devices_first")
        }
        if (initialError != null) {
            return initialError
        }
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
        val (delivered, deliveryError) = deliverToDeviceWithDetail(device, text, capturedAt)
        if (!delivered) {
            ClipboardPushDeduper.forget(text)
            val msg = deliveryError?.message ?: com.fileapex.i18n.AppI18n.t("clipboard_send_failed", device.deviceName)
            error(msg)
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
        val (delivered, deliveryError) = deliverToDeviceWithDetail(device, trimmed, capturedAt)
        if (!delivered) {
            ClipboardPushDeduper.forget(trimmed)
            val msg = deliveryError?.message ?: com.fileapex.i18n.AppI18n.t("clipboard_send_failed", device.deviceName)
            error(msg)
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
        // The key in the request is only a claim. The sender must be a paired device and the key must be
        // the one on record for it, otherwise anyone on the LAN could push text into the clipboard.
        val verifiedKey = verifiedSenderKey(senderDeviceId, senderPublicKey)
        val plaintext = ClipboardE2ee.decrypt(
            ciphertextBase64 = ciphertext,
            localDeviceId = localId,
            peerDeviceId = senderDeviceId.trim(),
            peerPublicKeyBase64 = verifiedKey, context = capturedAtEpochMs.toString()
        ).decodeToString()
        val prepared = ClipboardCopySignals.prepare(plaintext)
        if (prepared !is ClipboardCopySignals.Prepared.Ok) {
            error("empty_text")
        }
        ClipboardPushDeduper.remember(prepared.text)
        withContext(Dispatchers.Main) {
            PlatformClipboard.applyRemoteText(prepared.text, senderDeviceName)
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
        cancellableCatching {
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

    private suspend fun captureAndBroadcast(text: String, desktopPeersOnly: Boolean): Pair<Boolean, String?> {
        val hasTargets = mutex.withLock {
            val settings = FileApexServices.settings
            // Docker backup nodes hold a pairing key for pin exchange only; they have no clipboard.
            val paired = FileApexServices.deviceRepository.listDevices().filterNot { it.isDockerNode() }
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
                false
            } else {
                ClipboardPushDeduper.remember(text)
                pending = PendingShare(
                    text = text,
                    capturedAtEpochMs = TimeUtils.now(),
                    remainingIds = targets.toMutableSet()
                )
                true
            }
        }
        if (!hasTargets) return Pair(false, null)
        val initialError = attemptPending()
        scheduleRetries()
        return Pair(true, initialError)
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

    private suspend fun attemptPending(): String? {
        val snapshot = mutex.withLock { pending } ?: return null
        if (ClipboardSharePolicy.isExpired(snapshot.capturedAtEpochMs, TimeUtils.now())) {
            dropPending()
            return null
        }
        val remaining = snapshot.remainingIds.toList()
        if (remaining.isEmpty()) {
            dropPending()
            return null
        }
        var optInMessage: String? = null
        for (deviceId in remaining) {
            val device = FileApexServices.deviceRepository.getDevice(deviceId) ?: continue
            val (delivered, error) = cancellableCatching {
                deliverToDeviceWithDetail(device, snapshot.text, snapshot.capturedAtEpochMs)
            }.getOrElse { Pair(false, it) }
            if (delivered) {
                mutex.withLock {
                    pending?.remainingIds?.remove(deviceId)
                    if (pending?.remainingIds?.isEmpty() == true) {
                        pending = null
                    }
                }
            } else if (error != null && !error.message.isNullOrBlank()) {
                val msg = error.message.orEmpty()
                if (error is IllegalStateException) {
                    optInMessage = msg
                    mutex.withLock {
                        pending?.remainingIds?.remove(deviceId)
                        if (pending?.remainingIds?.isEmpty() == true) {
                            pending = null
                        }
                    }
                }
                showClipboardToast(msg)
            }
        }
        mutex.withLock {
            val live = pending ?: return optInMessage
            if (ClipboardSharePolicy.isExpired(live.capturedAtEpochMs, TimeUtils.now())) {
                pending = null
            }
        }
        return optInMessage
    }

    private suspend fun dropPending() {
        mutex.withLock {
            pending = null
        }
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun deliverToDeviceWithDetail(
        device: PairedDeviceEntity,
        text: String,
        capturedAtEpochMs: Long
    ): Pair<Boolean, Throwable?> {
        if (ClipboardSharePolicy.isExpired(capturedAtEpochMs, TimeUtils.now())) {
            return Pair(false, IllegalStateException(com.fileapex.i18n.AppI18n.t("clipboard_expired")))
        }
        val peerKey = resolvePeerPublicKey(device)
        if (peerKey.isBlank()) {
            println("ClipboardShareCoordinator: no public key for ${device.deviceName}")
            return Pair(false, IllegalStateException(com.fileapex.i18n.AppI18n.t("device_not_found")))
        }
        val identity = loadLocalIdentity()
        val ciphertext = ClipboardE2ee.encrypt(
            plaintext = text.encodeToByteArray(),
            localDeviceId = identity.deviceId,
            peerDeviceId = device.deviceId,
            peerPublicKeyBase64 = peerKey, context = capturedAtEpochMs.toString()
        )
        val pendingPayload = ClipboardSendRequest(
            senderDeviceId = identity.deviceId,
            senderDeviceName = identity.deviceName,
            senderPublicKey = ClipboardE2ee.publicKeyBase64(),
            ciphertext = ciphertext,
            capturedAtEpochMs = capturedAtEpochMs
        )
        var lastError: Throwable? = null
        val presence = FileApexServices.presenceMonitor
        val peerName = device.deviceName.ifBlank { com.fileapex.i18n.AppI18n.t("paired_device") }

        suspend fun askPeerToOptIn(host: String, port: Int): Throwable {
            cancellableCatching {
                FileApexServices.client.requestClipboardOptIn(
                    host = host,
                    port = port,
                    senderDeviceId = identity.deviceId,
                    senderDeviceName = identity.deviceName,
                    pendingPayload = pendingPayload
                )
            }
            val settings = FileApexServices.settings
            if (PeerPlatform.isAndroid(device.os, device.platform) && settings.googleAccountLinkEnabled.value) {
                cancellableCatching {
                    FcmWakeCoordinator.dispatchClipboardOptIn(
                        targetDeviceId = device.deviceId,
                        senderDeviceName = identity.deviceName,
                        pendingPayload = pendingPayload
                    )
                }
            }
            return IllegalStateException(
                com.fileapex.i18n.AppI18n.t("peer_clipboard_disabled_opt_in_sent", peerName)
            )
        }

        for (attempt in 0 until 2) {
            val route = presence.endpointIfOnline(device) ?: break
            val host = route.host
            val port = route.port
            val usable = routeKindForHost(host) != RouteKind.Lan || ClipboardSharePolicy.canUseLocalLan(
                lanConnected = isActiveLanConnectivity() && PeerLanHttpPolicy.canRoute(host),
                peerHost = host,
                localBindIps = NetworkUtils.lanBindCandidates()
            )
            if (!usable) break
            val status = cancellableCatching { FileApexServices.client.getClipboardStatus(host, port) }.getOrNull()
            if (status != null && !status.sharingEnabled) {
                return Pair(false, askPeerToOptIn(host, port))
            }
            val sent = cancellableCatching {
                FileApexServices.client.sendClipboard(
                    host = host,
                    port = port,
                    senderDeviceId = identity.deviceId,
                    senderDeviceName = identity.deviceName,
                    senderPublicKey = ClipboardE2ee.publicKeyBase64(),
                    ciphertext = ciphertext,
                    capturedAtEpochMs = capturedAtEpochMs
                )
            }
            if (sent.isSuccess) return Pair(true, null)
            val error = sent.exceptionOrNull()
            if (error?.message?.contains("clipboard_disabled") == true) {
                return Pair(false, askPeerToOptIn(host, port))
            }
            lastError = error
            println("ClipboardShareCoordinator: send to ${device.deviceName} failed - ${error?.message}")
            presence.reportRouteFailure(device, host)
        }
        val settings = FileApexServices.settings
        val fcmOk = ClipboardSharePolicy.canUseCellularFcm(
            viaCellularEnabled = settings.clipboardViaCellularEnabled.value,
            selfIsAndroid = currentPlatformLabel() == "Android",
            peerIsAndroid = PeerPlatform.isAndroid(device.os, device.platform),
            googleLinked = settings.googleAccountLinkEnabled.value
        )
        if (!fcmOk) return Pair(false, lastError)
        if (ciphertext.length > ClipboardSharePolicy.FCM_MAX_DATA_CHARS) return Pair(false, lastError)
        val fcmSent = FcmWakeCoordinator.dispatchClipboardShare(
            targetDeviceId = device.deviceId,
            senderPublicKey = ClipboardE2ee.publicKeyBase64(),
            ciphertext = ciphertext,
            capturedAtEpochMs = capturedAtEpochMs,
            senderDeviceName = identity.deviceName
        )
        return Pair(fcmSent, if (fcmSent) null else lastError)
    }

    /** Stored key, else the one in the peer's own cloud record. Never a key fetched from the live peer. */
    internal suspend fun recordedPeerPublicKey(device: PairedDeviceEntity): String {
        val stored = device.publicKey.trim()
        if (stored.isNotEmpty()) return stored
        return GoogleLinkCoordinator.cloudRecordFor(device.deviceId)?.clipboardPublicKey.orEmpty().trim()
    }

    /** Returns the recorded key when [claimedKey] matches it for a paired, non-removed sender; else throws. */
    internal suspend fun verifiedSenderKey(senderDeviceId: String, claimedKey: String): String {
        val device = FileApexServices.deviceRepository.getDevice(senderDeviceId.trim())
        val cloudKey = if (device != null && device.publicKey.isBlank()) {
            GoogleLinkCoordinator.cloudRecordFor(device.deviceId)?.clipboardPublicKey.orEmpty()
        } else {
            ""
        }
        return ClipboardSenderCheck.verify(device, cloudKey, claimedKey)
    }

    internal suspend fun resolvePeerPublicKey(device: PairedDeviceEntity): String {
        val stored = device.publicKey.trim()
        if (stored.isNotEmpty()) return stored
        val cloud = GoogleLinkCoordinator.cloudRecordFor(device.deviceId)
            ?.clipboardPublicKey.orEmpty().trim()
        if (cloud.isNotEmpty()) return cloud
        val route = FileApexServices.presenceMonitor.endpointIfOnline(device) ?: return ""
        val live = cancellableCatching {
            FileApexServices.client.fetchPeerNodeState(route.host, route.port)
        }.getOrNull() ?: return ""
        val key = live.publicKey.trim()
        if (key.isNotEmpty()) {
            cancellableCatching {
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
        cancellableCatching {
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
    BriefToast.show(message)
}
