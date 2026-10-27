package com.fileapex.ui.notes

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.cloud.drive.NotesAttachmentDecision
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.UserFacingErrors
import com.fileapex.platform.PickedLocalFile
import com.fileapex.ui.ExplorerEntryIcons
import com.fileapex.ui.NotesAttachmentTransportState
import com.fileapex.ui.loadNotesAttachmentBitmap
import com.fileapex.ui.notesAttachmentIsImage
import com.fileapex.ui.predictOutgoingAttachmentCardSize
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal fun NotesScope.acceptPickedAttachment(picked: PickedLocalFile) {
    with(frame) { with(live) {
        pendingAttachmentName = picked.displayName
        pendingAttachmentPath = picked.absolutePath
        pendingAttachmentSize = picked.sizeBytes
        attachError = null
    } }
}

internal fun NotesScope.finishRelayGrant(picked: PickedLocalFile?) {
    with(frame) { with(live) {
        val settings = FileApexServices.settings
        settings.setCellularEnabled(true)
        settings.setGoogleDriveRelayEnabled(true)
        settings.setCellularSendPromptAcknowledged(true)
        settings.setCellularReceivePromptAcknowledged(true)
        com.fileapex.cloud.drive.DriveRelayCoordinator.applySchedulerFromSettings()
        if (GoogleDriveAuth.hasGrant()) {
            com.fileapex.platform.DriveRelayNotifier.onDriveEnabledAndGranted()
        }
        picked?.let { acceptPickedAttachment(it) }
        pendingAcceptAfterRelay = null
        pendingRelayPick = null
        relayOptIn = null
        showDrivePermission = false
    } }
}

internal fun NotesScope.beginRelayOptIn(picked: PickedLocalFile) {
    with(frame) { with(live) {
        FileApexServices.settings.setDriveRelayOptInPromptShown(true)
        pendingAcceptAfterRelay = picked
        when {
            !FileApexServices.settings.googleAccountLinkEnabled.value -> launchSignIn()
            !GoogleDriveAuth.hasGrant() -> showDrivePermission = true
            else -> finishRelayGrant(picked)
        }
    } }
}

internal suspend fun NotesScope.scrollNotesToLatest() {
    with(frame) { with(live) {
        if (!focusNoteId.isNullOrBlank()) return
        if (visualRows.isEmpty() && !holdingTransportSlot) return
        val divider = visualRows.indexOfFirst { it is com.fileapex.presentation.NotesListRow.NewDivider }
        // Once per visit, to the oldest post that was unread on opening; later arrivals are newer than that spot.
        if (divider >= 0 && !jumpedToNew) {
            jumpedToNew = true
            listState.scrollToItem(divider + if (holdingTransportSlot) 1 else 0)
            listState.scrollBy(-listState.layoutInfo.viewportSize.height * 0.85f)
            return
        }
        listState.scrollToItem(0)
    } }
}

internal fun NotesScope.flushPendingSend(text: String?) {
    with(frame) { with(live) {
        if (text == null) return
        val name = pendingAttachmentName
        val path = pendingAttachmentPath
        val size = pendingAttachmentSize
        val preview = attachedPreview
        val sourceRect = attachedChipRect
        val overlay = overlayCoords
        if (text.isBlank() && path.isNullOrBlank()) return
        inputContent = ""
        pendingAttachmentName = null
        pendingAttachmentPath = null
        pendingAttachmentSize = 0L
        attachedPreview = null
        val origin = sourceRect
        if (
            !path.isNullOrBlank() &&
            origin != null &&
            origin.width > 4f &&
            origin.height > 4f &&
            overlay != null &&
            pendingTransport == null &&
            (transport == null || transport?.settled == true)
        ) {
            val listWidth = listRect?.width ?: origin.width.coerceAtLeast(280f)
            val caption = text.trim().takeIf { body ->
                body.isNotEmpty() && body != name
            }.orEmpty()
            val (cardW, cardH) = predictOutgoingAttachmentCardSize(
                density = density,
                listWidthPx = listWidth,
                fileName = name.orEmpty(),
                caption = caption,
                includeDriveBadge = DriveRelayPolicy.canSend()
            )
            pendingTransport = PendingNotesTransport(
                sourceRect = origin,
                bitmap = preview,
                isImage = notesAttachmentIsImage(name),
                accent = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                icon = if (name.isNullOrBlank()) {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                } else {
                    ExplorerEntryIcons.iconForFile(name, "")
                },
                text = text,
                caption = caption,
                path = path,
                name = name,
                size = size,
                cardWidthPx = cardW,
                cardHeightPx = cardH
            )
            departingChipName = name
            departingChipPreview = preview
            return
        }
        scope.launch {
            FileApexServices.noteRepository.sendNote(
                content = text,
                attachmentPath = path,
                attachmentFileName = name,
                attachmentSizeBytes = size
            )
        }
    } }
}

internal fun NotesScope.lockTransportTarget(dest: Rect, pending: PendingNotesTransport) {
    with(frame) { with(live) {
        if (pendingTransport !== pending) return
        if (dest.width < 4f || dest.height < 4f) return
        pendingTransport = null
        val targetNoteId = "msg-" + TimeUtils.now() + "-" + (1000..9999).random()
        val session = NotesAttachmentTransportState(
            sourceRect = pending.sourceRect,
            destRect = dest,
            cardWidthPx = pending.cardWidthPx,
            cardHeightPx = pending.cardHeightPx,
            bitmap = pending.bitmap,
            isImage = pending.isImage,
            accent = pending.accent,
            icon = pending.icon,
            fileName = pending.name.orEmpty(),
            caption = pending.caption,
            attachmentPath = pending.path
        )
        session.assemblingNoteId = targetNoteId
        if (pending.bitmap != null) {
            bubbleThumbs[targetNoteId] = pending.bitmap
        }
        transport = session
        scope.launch(Dispatchers.IO) {
            cancellableCatching {
                FileApexServices.noteRepository.sendNote(
                    content = pending.text,
                    attachmentPath = pending.path,
                    attachmentFileName = pending.name,
                    attachmentSizeBytes = pending.size,
                    assignedNoteId = targetNoteId
                )
            }.onFailure { error ->
                session.streamDone = true
                session.settled = true
                attachError = UserFacingErrors.message(error, "failed_send_attachment")
            }
        }
    } }
}

internal fun NotesScope.doSendNote(text: String) {
    with(frame) { with(live) {
        val attachmentPath = pendingAttachmentPath
        if (!attachmentPath.isNullOrBlank()) {
            when (val decision = DriveRelayPolicy.evaluateNotesAttachment(pendingAttachmentSize)) {
                NotesAttachmentDecision.AllowLan,
                NotesAttachmentDecision.AllowRelay -> Unit
                is NotesAttachmentDecision.OfferRelayOptIn -> {
                    attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                        "offline Notes limit. Enable Google Drive Relay to send this file."
                    return
                }
                is NotesAttachmentDecision.NeedsRelayEnabled -> {
                    attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                        "offline Notes limit. Enable Google Drive Relay in Settings → Cellular."
                    return
                }
                is NotesAttachmentDecision.TooLargeForRelay -> {
                    attachError = "${decision.fileLabel} is over the ${decision.relayLimitLabel} " +
                        "Google Drive Relay limit."
                    return
                }
                is NotesAttachmentDecision.TooLargeForLan -> {
                    attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                        "Notes attachment limit."
                    return
                }
            }
        }
        if (!FileApexServices.settings.notesNotificationPromptShown.value) {
            pendingNoteToSend = text
            showNotesPermissionPrompt = true
        } else {
            flushPendingSend(text)
        }
    } }
}

internal fun NotesScope.originFromDrop(rootOffset: Offset?): Rect? {
    with(frame) { with(live) {
        val overlay = overlayCoords
        if (overlay != null && rootOffset != null && rootOffset != Offset.Unspecified) {
            val origin = overlay.localToRoot(Offset.Zero)
            val local = Offset(rootOffset.x - origin.x, rootOffset.y - origin.y)
            if (local.x.isFinite() && local.y.isFinite()) {
                return Rect(local.x - 18f, local.y - 18f, local.x + 18f, local.y + 18f)
            }
        }
        val area = listRect
        if (area != null && area.width > 4f) {
            return Rect(
                area.center.x - 18f,
                area.bottom - 40f,
                area.center.x + 18f,
                area.bottom - 4f
            )
        }
        if (overlay != null) {
            val cx = overlay.size.width / 2f
            val cy = overlay.size.height / 2f
            return Rect(cx - 18f, cy - 18f, cx + 18f, cy + 18f)
        }
        return null
    } }
}

internal fun NotesScope.startDroppedAttachment(picked: PickedLocalFile, rootOffset: Offset?) {
    with(frame) { with(live) {
        when (val decision = DriveRelayPolicy.evaluateNotesAttachment(picked.sizeBytes)) {
            NotesAttachmentDecision.AllowLan,
            NotesAttachmentDecision.AllowRelay -> Unit
            is NotesAttachmentDecision.OfferRelayOptIn -> {
                pendingRelayPick = picked
                relayOptIn = decision
                return
            }
            is NotesAttachmentDecision.NeedsRelayEnabled -> {
                attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                    "offline Notes limit. Enable Google Drive Relay in Settings → Cellular " +
                    "to send larger files."
                return
            }
            is NotesAttachmentDecision.TooLargeForRelay -> {
                attachError = "${decision.fileLabel} is over the ${decision.relayLimitLabel} " +
                    "Google Drive Relay limit. Choose a smaller file or raise the Relay size " +
                    "limit in Settings → Cellular."
                return
            }
            is NotesAttachmentDecision.TooLargeForLan -> {
                attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                    "Notes attachment limit. Choose a smaller file."
                return
            }
        }
        if (pendingTransport != null || (transport != null && transport?.settled != true)) {
            dropQueue.addLast(picked)
            return
        }
        val captionText = inputContent.trim()
        if (!FileApexServices.settings.notesNotificationPromptShown.value) {
            pendingAttachmentName = picked.displayName
            pendingAttachmentPath = picked.absolutePath
            pendingAttachmentSize = picked.sizeBytes
            pendingNoteToSend = captionText
            showNotesPermissionPrompt = true
            return
        }
        scope.launch {
            val preview = loadNotesAttachmentBitmap(picked.absolutePath, picked.displayName)
            val origin = originFromDrop(rootOffset) ?: return@launch
            val listWidth = listRect?.width ?: origin.width.coerceAtLeast(280f)
            val caption = captionText.takeIf { body ->
                body.isNotEmpty() && body != picked.displayName
            }.orEmpty()
            val (cardW, cardH) = predictOutgoingAttachmentCardSize(
                density = density,
                listWidthPx = listWidth,
                fileName = picked.displayName,
                caption = caption,
                includeDriveBadge = DriveRelayPolicy.canSend()
            )
            inputContent = ""
            pendingTransport = PendingNotesTransport(
                sourceRect = origin,
                bitmap = preview,
                isImage = notesAttachmentIsImage(picked.displayName),
                accent = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                icon = ExplorerEntryIcons.iconForFile(picked.displayName, ""),
                text = captionText,
                caption = caption,
                path = picked.absolutePath,
                name = picked.displayName,
                size = picked.sizeBytes,
                cardWidthPx = cardW,
                cardHeightPx = cardH
            )
        }
    } }
}

internal fun NotesScope.handleDroppedPaths(paths: List<String>, rootOffset: Offset?) {
    with(frame) { with(live) {
        val files = paths.mapNotNull { pickedFileFromDroppedPath(it) }
        if (files.isEmpty()) {
            if (paths.isNotEmpty()) {
                attachError = AppI18n.t("notes_drop_file_only")
            }
            return
        }
        if (rootOffset != null) lastDropRootOffset = rootOffset
        dropQueue.addAll(files)
        if (pendingTransport != null || (transport != null && transport?.settled != true)) return
        val next = dropQueue.removeFirstOrNull() ?: return
        startDroppedAttachment(next, lastDropRootOffset)
    } }
}
