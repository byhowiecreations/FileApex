package com.fileapex.ui

import com.fileapex.i18n.stringRes

import com.fileapex.i18n.AppI18n

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.fileapex.cloud.GoogleLinkCoordinator
import com.fileapex.cloud.drive.DriveRelayPolicy
import com.fileapex.cloud.drive.GoogleDriveAuth
import com.fileapex.cloud.drive.NotesAttachmentDecision
import com.fileapex.data.bulletin.BulletinRemoteFilePurgeCoordinator
import com.fileapex.data.bulletin.BulletinRemoteFilePurgeHandler
import com.fileapex.data.bulletin.BulletinRemotePurgePrompt
import com.fileapex.data.bulletin.bulletinDeleteContentKind
import com.fileapex.data.bulletin.hasBulletinBinaryAttachment
import com.fileapex.data.note.NoteRecord
import com.fileapex.platform.BriefToast
import com.fileapex.platform.PickedLocalFile
import com.fileapex.platform.openLocalFile
import com.fileapex.platform.rememberDownloadsFilePicker
import com.fileapex.platform.rememberGoogleDriveAuthLauncher
import com.fileapex.platform.rememberGoogleSignInLauncher
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.ui.dialogs.GoogleDrivePermissionDialog
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

@Composable
fun NotesScreen(
    onBack: () -> Unit,
    focusNoteId: String? = null,
    onFocusNoteConsumed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS || currentTheme == AppTheme.KINETIC_SPHERE
    val rawNotes by FileApexServices.noteRepository.notes.collectAsState()
    val downloadingAttachmentIds by FileApexServices.noteRepository.downloadingAttachmentIds.collectAsState()
    val displayNotes = remember(rawNotes) { rawNotes.sortedBy { it.epochMs } }
    var inputContent by remember { mutableStateOf("") }
    var noteToDelete by remember { mutableStateOf<NoteRecord?>(null) }
    var pendingRemotePurgeDelete by remember { mutableStateOf<NoteRecord?>(null) }
    var remotePurgePrompt by remember { mutableStateOf<BulletinRemotePurgePrompt?>(null) }
    var showNotesPermissionPrompt by remember { mutableStateOf(false) }
    var pendingNoteToSend by remember { mutableStateOf<String?>(null) }
    var attachError by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentName by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentPath by remember { mutableStateOf<String?>(null) }
    var pendingAttachmentSize by remember { mutableLongStateOf(0L) }
    var revealedNoteId by remember { mutableStateOf<String?>(null) }
    var highlightedNoteId by remember { mutableStateOf<String?>(null) }
    var relayOptIn by remember { mutableStateOf<NotesAttachmentDecision.OfferRelayOptIn?>(null) }
    var pendingRelayPick by remember { mutableStateOf<PickedLocalFile?>(null) }
    var showDrivePermission by remember { mutableStateOf(false) }
    var pendingAcceptAfterRelay by remember { mutableStateOf<PickedLocalFile?>(null) }
    var attachedPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var overlayCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var attachedChipRect by remember { mutableStateOf<Rect?>(null) }
    var listRect by remember { mutableStateOf<Rect?>(null) }
    var transport by remember { mutableStateOf<NotesAttachmentTransportState?>(null) }
    var pendingTransport by remember { mutableStateOf<PendingNotesTransport?>(null) }
    var departingChipName by remember { mutableStateOf<String?>(null) }
    var departingChipPreview by remember { mutableStateOf<ImageBitmap?>(null) }
    var dropHover by remember { mutableStateOf(false) }
    var lastDropRootOffset by remember { mutableStateOf<Offset?>(null) }
    val dropQueue = remember { ArrayDeque<PickedLocalFile>() }
    val bubbleThumbs = remember { mutableStateMapOf<String, ImageBitmap?>() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val notesForList = remember(displayNotes, transport?.assemblingNoteId, transport?.settled) {
        val hideId = transport?.takeIf { it.settled != true }?.assemblingNoteId
        if (hideId.isNullOrBlank()) displayNotes else displayNotes.filterNot { it.noteId == hideId }
    }
    val listRows = remember(notesForList) { notesListRows(notesForList) }
    val visualRows = remember(listRows) { listRows.asReversed() }
    val holdingTransportSlot = pendingTransport != null ||
        (transport != null && transport?.settled != true)

    fun acceptPickedAttachment(picked: PickedLocalFile) {
        pendingAttachmentName = picked.displayName
        pendingAttachmentPath = picked.absolutePath
        pendingAttachmentSize = picked.sizeBytes
        attachError = null
    }

    fun finishRelayGrant(picked: PickedLocalFile?) {
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
    }

    val launchDriveAuth = rememberGoogleDriveAuthLauncher { granted, errorMessage ->
        if (granted) {
            finishRelayGrant(pendingAcceptAfterRelay)
        } else {
            attachError = errorMessage ?: AppI18n.t("drive_relay_not_enabled")
            pendingAcceptAfterRelay = null
        }
    }

    val launchSignIn = rememberGoogleSignInLauncher { idToken, email, errorMessage ->
        if (idToken.isNullOrBlank()) {
            attachError = errorMessage ?: AppI18n.t("google_signin_cancelled")
            pendingAcceptAfterRelay = null
            return@rememberGoogleSignInLauncher
        }
        scope.launch {
            runCatching {
                GoogleLinkCoordinator.linkWithGoogleIdToken(idToken, email)
            }.onSuccess {
                FileApexServices.settings.setCellularEnabled(true)
                if (GoogleDriveAuth.hasGrant()) {
                    finishRelayGrant(pendingAcceptAfterRelay)
                } else {
                    showDrivePermission = true
                }
            }.onFailure { error ->
                attachError = error.message ?: AppI18n.t("google_link_failed")
                pendingAcceptAfterRelay = null
            }
        }
    }

    fun beginRelayOptIn(picked: PickedLocalFile) {
        FileApexServices.settings.setDriveRelayOptInPromptShown(true)
        pendingAcceptAfterRelay = picked
        when {
            !FileApexServices.settings.googleAccountLinkEnabled.value -> launchSignIn()
            !GoogleDriveAuth.hasGrant() -> showDrivePermission = true
            else -> finishRelayGrant(picked)
        }
    }

    val pickAttachment = rememberDownloadsFilePicker { picked ->
        if (picked == null) return@rememberDownloadsFilePicker
        when (val decision = DriveRelayPolicy.evaluateNotesAttachment(picked.sizeBytes)) {
            NotesAttachmentDecision.AllowLan,
            NotesAttachmentDecision.AllowRelay -> acceptPickedAttachment(picked)
            is NotesAttachmentDecision.OfferRelayOptIn -> {
                pendingRelayPick = picked
                relayOptIn = decision
            }
            is NotesAttachmentDecision.NeedsRelayEnabled -> {
                attachError = "${decision.fileLabel} is over the ${decision.lanLimitLabel} " +
                    "offline Notes limit. Enable Google Drive Relay in Settings → Cellular " +
                    "to send larger files."
            }
            is NotesAttachmentDecision.TooLargeForRelay -> {
                attachError = "${decision.fileLabel} is over the ${decision.relayLimitLabel} " +
                    "Google Drive Relay limit. Choose a smaller file or raise the Relay size " +
                    "limit in Settings → Cellular."
            }
        }
    }

    val listState = rememberLazyListState()

    suspend fun scrollNotesToLatest() {
        if (!focusNoteId.isNullOrBlank()) return
        if (visualRows.isEmpty() && !holdingTransportSlot) return
        listState.scrollToItem(0)
    }

    LaunchedEffect(pendingAttachmentPath, pendingAttachmentName) {
        attachedPreview = loadNotesAttachmentBitmap(pendingAttachmentPath, pendingAttachmentName)
    }

    LaunchedEffect(transport?.settled) {
        val session = transport
        if (session?.settled == true) {
            transport = null
            pendingTransport = null
            departingChipName = null
            departingChipPreview = null
        }
    }

    LaunchedEffect(transport, transport?.streamDone, transport?.assemblingNoteId) {
        val session = transport ?: return@LaunchedEffect
        if (!session.streamDone) return@LaunchedEffect
        if (session.assemblingNoteId.isNullOrBlank()) return@LaunchedEffect
        session.deliveryLabel = NOTES_SENT_LABEL
        delay(NOTES_SENT_HOLD_MS)
        if (transport === session) {
            session.settled = true
        }
    }

    LaunchedEffect(Unit) {
        BulletinRemoteFilePurgeCoordinator.pendingPrompts.collect { prompt ->
            remotePurgePrompt = prompt
        }
    }

    LaunchedEffect(displayNotes) {
        val liveIds = displayNotes.map { it.noteId }.toSet()
        val staleIds = bubbleThumbs.keys.filter { it !in liveIds }
        staleIds.forEach { bubbleThumbs.remove(it) }
        for (note in displayNotes) {
            if (bubbleThumbs.containsKey(note.noteId)) continue
            if (!notesAttachmentIsImage(note.attachmentFileName)) continue
            val path = note.attachmentLocalPath
            val thumb = if (!path.isNullOrBlank()) {
                loadNotesAttachmentBitmap(path, note.attachmentFileName)
            } else {
                loadNotesInlinePreviewBitmap(note.attachmentPreviewBase64)
            }
            if (thumb != null) {
                bubbleThumbs[note.noteId] = thumb
            }
        }
    }

    LaunchedEffect(listRows.size, pendingTransport != null, transport?.settled, transport?.streamDone, focusNoteId) {
        scrollNotesToLatest()
    }

    LaunchedEffect(focusNoteId, listRows) {
        val id = focusNoteId?.trim().orEmpty()
        if (id.isEmpty()) return@LaunchedEffect
        val rowIndex = visualRows.indexOfFirst { row ->
            row is NotesListRow.Bubble && row.note.noteId == id
        }
        if (rowIndex < 0) return@LaunchedEffect
        val index = if (holdingTransportSlot) rowIndex + 1 else rowIndex
        listState.scrollToItem(index)
        highlightedNoteId = id
        onFocusNoteConsumed()
    }

    LaunchedEffect(highlightedNoteId) {
        val id = highlightedNoteId ?: return@LaunchedEffect
        delay(2200)
        if (highlightedNoteId == id) highlightedNoteId = null
    }

    LaunchedEffect(focusNoteId) {
        val id = focusNoteId?.trim().orEmpty()
        if (id.isEmpty()) return@LaunchedEffect
        delay(4000)
        if (focusNoteId == id) onFocusNoteConsumed()
    }

    val backgroundColor = when (currentTheme) {
        AppTheme.FLUX_GLASS -> Color.Transparent
        AppTheme.KINETIC_SPHERE -> Color(0xFF030B14)
        else -> MaterialTheme.colorScheme.background
    }
    val cardBg = when (currentTheme) {
        AppTheme.FLUX_GLASS -> Color(0x330D1F29)
        AppTheme.KINETIC_SPHERE -> Color(0x440A1D2E)
        else -> MaterialTheme.colorScheme.surface
    }
    val textColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface
    val subTextColor = if (isCustomGlass) Color.White.copy(alpha = 0.70f) else MaterialTheme.colorScheme.onSurfaceVariant

    fun flushPendingSend(text: String?) {
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
    }

    fun lockTransportTarget(dest: Rect, pending: PendingNotesTransport) {
        if (pendingTransport !== pending) return
        if (dest.width < 4f || dest.height < 4f) return
        pendingTransport = null
        val session = NotesAttachmentTransportState(
            sourceRect = pending.sourceRect,
            destRect = dest,
            cardWidthPx = pending.cardWidthPx,
            cardHeightPx = pending.cardHeightPx,
            bitmap = pending.bitmap,
            isImage = pending.isImage,
            accent = pending.accent,
            icon = pending.icon,
            fileName = pending.name,
            caption = pending.caption,
            attachmentPath = pending.path
        )
        transport = session
        scope.launch {
            runCatching {
                FileApexServices.noteRepository.sendNote(
                    content = pending.text,
                    attachmentPath = pending.path,
                    attachmentFileName = pending.name,
                    attachmentSizeBytes = pending.size
                )
            }.onSuccess { record ->
                session.assemblingNoteId = record.noteId
                if (pending.bitmap != null) {
                    bubbleThumbs[record.noteId] = pending.bitmap
                }
            }.onFailure { error ->
                session.streamDone = true
                session.settled = true
                attachError = error.message ?: AppI18n.t("failed_send_attachment")
            }
        }
    }

    fun doSendNote(text: String) {
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
            }
        }
        if (!FileApexServices.settings.notesNotificationPromptShown.value) {
            pendingNoteToSend = text
            showNotesPermissionPrompt = true
        } else {
            flushPendingSend(text)
        }
    }

    fun originFromDrop(rootOffset: Offset?): Rect? {
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
    }

    fun startDroppedAttachment(picked: PickedLocalFile, rootOffset: Offset?) {
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
    }

    fun handleDroppedPaths(paths: List<String>, rootOffset: Offset?) {
        val files = paths.mapNotNull { pickedFileFromDroppedPath(it) }
        if (files.isEmpty()) {
            if (paths.isNotEmpty()) {
                attachError = "Drop a file onto Notes. Folders are not sent as attachments."
            }
            return
        }
        if (rootOffset != null) lastDropRootOffset = rootOffset
        dropQueue.addAll(files)
        if (pendingTransport != null || (transport != null && transport?.settled != true)) return
        val next = dropQueue.removeFirstOrNull() ?: return
        startDroppedAttachment(next, lastDropRootOffset)
    }

    LaunchedEffect(transport == null, dropQueue.size) {
        if (transport != null || pendingTransport != null) return@LaunchedEffect
        val next = dropQueue.removeFirstOrNull() ?: return@LaunchedEffect
        startDroppedAttachment(next, lastDropRootOffset)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (dropHover) {
                    if (isCustomGlass) Color(0xFF00E676).copy(alpha = 0.10f) else FileApexTeal.copy(alpha = 0.10f)
                } else {
                    Color.Transparent
                }
            )
            .deviceFileDropTarget(
                enabled = true,
                onHoverChange = { dropHover = it },
                onDropPosition = { lastDropRootOffset = it },
                onFilesDropped = { paths -> handleDroppedPaths(paths, lastDropRootOffset) }
            )
            .onGloballyPositioned { overlayCoords = it }
    ) {
    Scaffold(
        containerColor = backgroundColor,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes("back"),
                        tint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringRes("bulletin_board"),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        ),
                        color = textColor
                    )
                    Text(
                        text = stringRes("notes_subtitle"),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = subTextColor
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (notesForList.isEmpty() && !holdingTransportSlot) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Note,
                            contentDescription = null,
                            tint = if (isCustomGlass) Color(0xFF00E676).copy(alpha = 0.6f) else FileApexTeal.copy(alpha = 0.6f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringRes("no_notes_yet"),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringRes("notes_empty_hint"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = subTextColor
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { coords ->
                            overlayCoords?.let { overlay ->
                                listRect = coords.rectIn(overlay)
                            }
                        },
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    reverseLayout = true
                ) {
                    if (holdingTransportSlot) {
                        item(key = "notes-transport-reserve") {
                            val pending = pendingTransport
                            val session = transport
                            val assemblingNote = session?.assemblingNoteId?.let { id ->
                                displayNotes.firstOrNull { it.noteId == id }
                            }
                            val outgoingNote = assemblingNote ?: outgoingPlaceholderNote(
                                fileName = pending?.name ?: session?.fileName,
                                caption = pending?.caption ?: session?.caption.orEmpty(),
                                path = pending?.path ?: session?.attachmentPath
                            )
                            val outgoingThumb = assemblingNote?.noteId?.let { bubbleThumbs[it] }
                                ?: pending?.bitmap
                                ?: session?.bitmap
                            NoteBubbleItem(
                                item = outgoingNote,
                                cardBg = cardBg,
                                textColor = textColor,
                                subTextColor = subTextColor,
                                isCustomGlass = isCustomGlass,
                                revealed = false,
                                assembling = session?.streamDone != true,
                                thumbnail = outgoingThumb,
                                footerLabel = when {
                                    session?.settled == true -> null
                                    session?.deliveryLabel != null -> session.deliveryLabel
                                    else -> NOTES_SENDING_LABEL
                                },
                                gesturesEnabled = false,
                                onBubblePositioned = { coords ->
                                    val waiting = pendingTransport ?: return@NoteBubbleItem
                                    overlayCoords?.let { overlay ->
                                        lockTransportTarget(coords.rectIn(overlay), waiting)
                                    }
                                },
                                onRevealedChange = {},
                                onCloseAnyReveal = {},
                                onDeleteClick = {},
                                onLockClick = {},
                                onOpenAttachment = {}
                            )
                        }
                    }
                    items(visualRows, key = { row ->
                        when (row) {
                            is NotesListRow.DayHeader -> "day-${row.dayKey}"
                            is NotesListRow.Bubble -> row.note.noteId
                        }
                    }) { row ->
                        when (row) {
                            is NotesListRow.DayHeader -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = row.label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = subTextColor
                                    )
                                }
                            }
                            is NotesListRow.Bubble -> {
                                NoteBubbleItem(
                                    item = row.note,
                                    cardBg = cardBg,
                                    textColor = textColor,
                                    subTextColor = subTextColor,
                                    isCustomGlass = isCustomGlass,
                                    revealed = revealedNoteId == row.note.noteId,
                                    highlighted = highlightedNoteId == row.note.noteId,
                                    assembling = false,
                                    thumbnail = bubbleThumbs[row.note.noteId],
                                    isDownloadingAttachment = row.note.noteId in downloadingAttachmentIds,
                                    onBubblePositioned = {},
                                    onRevealedChange = { open ->
                                        revealedNoteId = if (open) row.note.noteId else null
                                    },
                                    onCloseAnyReveal = { revealedNoteId = null },
                                    onDeleteClick = {
                                        revealedNoteId = null
                                        noteToDelete = row.note
                                    },
                                    onLockClick = {
                                        revealedNoteId = null
                                        scope.launch {
                                            FileApexServices.noteRepository.setAttachmentPinned(
                                                row.note.noteId,
                                                !row.note.attachmentPinned
                                            )
                                        }
                                    },
                                    onOpenAttachment = {
                                        val name = row.note.attachmentFileName.orEmpty()
                                        scope.launch {
                                            val path = FileApexServices.noteRepository.fetchAttachmentIfNeeded(
                                                row.note.noteId
                                            )
                                            if (!path.isNullOrBlank()) {
                                                openLocalFile(path, name)
                                            } else if (
                                                row.note.noteId !in downloadingAttachmentIds &&
                                                FileApexServices.noteRepository.attachmentNeedsDownload(row.note)
                                            ) {
                                                BriefToast.show(AppI18n.t("could_not_download_attachment"))
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            val chipName = pendingAttachmentName ?: departingChipName
            if (chipName != null) {
                val chipPreview = attachedPreview ?: departingChipPreview
                val chipDeparted = pendingAttachmentName == null
                Row(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .graphicsLayer { alpha = if (chipDeparted) 0f else 1f }
                        .onGloballyPositioned { coords ->
                            overlayCoords?.let { overlay ->
                                attachedChipRect = coords.rectIn(overlay)
                            }
                        }
                        .clip(RoundedCornerShape(10.dp)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (chipPreview != null) {
                        Image(
                            bitmap = chipPreview,
                            contentDescription = null,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = ExplorerEntryIcons.iconForFile(chipName, ""),
                            contentDescription = null,
                            tint = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringRes("attached_name", chipName),
                        style = MaterialTheme.typography.labelMedium,
                        color = subTextColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Composer Row — 15% shorter than Material3's 56.dp outlined field so the
            // placeholder stays on one line.
            val composerInteraction = remember { MutableInteractionSource() }
            val composerFocused by composerInteraction.collectIsFocusedAsState()
            val composerTextStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = 13.sp,
                color = textColor
            )
            val composerBorder = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
            val composerIdleBorder = if (isCustomGlass) {
                Color.White.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.outline
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { pickAttachment() }) {
                    Icon(
                        imageVector = Icons.Filled.AttachFile,
                        contentDescription = stringRes("attach_file"),
                        tint = if (pendingAttachmentPath != null) {
                            if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        } else {
                            subTextColor
                        }
                    )
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .height(NOTES_COMPOSER_HEIGHT),
                    shape = RoundedCornerShape(20.dp),
                    color = Color.Transparent,
                    border = BorderStroke(
                        1.dp,
                        if (composerFocused) composerBorder else composerIdleBorder
                    )
                ) {
                    BasicTextField(
                        value = inputContent,
                        onValueChange = { inputContent = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        singleLine = true,
                        textStyle = composerTextStyle,
                        cursorBrush = SolidColor(composerBorder),
                        interactionSource = composerInteraction,
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (inputContent.isEmpty()) {
                                    Text(
                                        text = stringRes("broadcast_or_attach"),
                                        color = subTextColor,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                inner()
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    onClick = {
                        val text = inputContent.trim()
                        if (text.isNotEmpty() || !pendingAttachmentPath.isNullOrBlank()) {
                            doSendNote(text)
                        }
                    },
                    enabled = inputContent.trim().isNotEmpty() || !pendingAttachmentPath.isNullOrBlank(),
                    shape = CircleShape,
                    color = if (inputContent.trim().isNotEmpty() || !pendingAttachmentPath.isNullOrBlank()) (if (isCustomGlass) Color(0xFF00E676) else FileApexTeal) else Color.Gray.copy(alpha = 0.4f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringRes("send_note"),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }

    val activeTransport = transport
    if (activeTransport != null && !activeTransport.streamDone) {
        NotesAttachmentTransportOverlay(
            state = activeTransport,
            modifier = Modifier.fillMaxSize()
        )
    }
    }

    if (showNotesPermissionPrompt) {
        AlertDialog(
            onDismissRequest = {
                FileApexServices.settings.setNotesNotificationsEnabled(false)
                FileApexServices.settings.setNotesNotificationPromptShown(true)
                val text = pendingNoteToSend
                pendingNoteToSend = null
                showNotesPermissionPrompt = false
                flushPendingSend(text)
            },
            title = { Text(stringRes("enable_note_notifications")) },
            text = { Text(stringRes("note_notifications_prompt")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        FileApexServices.settings.setNotesNotificationsEnabled(true)
                        FileApexServices.settings.setNotesNotificationPromptShown(true)
                        val text = pendingNoteToSend
                        pendingNoteToSend = null
                        showNotesPermissionPrompt = false
                        flushPendingSend(text)
                    }
                ) {
                    Text(stringRes("enable_notifications"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        FileApexServices.settings.setNotesNotificationsEnabled(false)
                        FileApexServices.settings.setNotesNotificationPromptShown(true)
                        val text = pendingNoteToSend
                        pendingNoteToSend = null
                        showNotesPermissionPrompt = false
                        flushPendingSend(text)
                    }
                ) {
                    Text(stringRes("not_now"))
                }
            }
        )
    }

    attachError?.let { message ->
        AlertDialog(
            onDismissRequest = { attachError = null },
            title = { Text(stringRes("attachment")) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { attachError = null }) { Text(stringRes("ok")) }
            }
        )
    }

    relayOptIn?.let { offer ->
        val picked = pendingRelayPick
        AlertDialog(
            onDismissRequest = {
                FileApexServices.settings.setDriveRelayOptInPromptShown(true)
                relayOptIn = null
                pendingRelayPick = null
            },
            title = { Text(stringRes("file_too_large_offline_notes")) },
            text = {
                Text(
                    stringRes(
                        "file_over_notes_limit",
                        offer.fileLabel,
                        offer.lanLimitLabel,
                        offer.relayLimitLabel
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        relayOptIn = null
                        if (picked != null) beginRelayOptIn(picked)
                    }
                ) { Text(stringRes("enable_drive_relay")) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        FileApexServices.settings.setDriveRelayOptInPromptShown(true)
                        relayOptIn = null
                        pendingRelayPick = null
                    }
                ) { Text(stringRes("not_now")) }
            }
        )
    }

    if (showDrivePermission) {
        GoogleDrivePermissionDialog(
            onGrant = {
                showDrivePermission = false
                launchDriveAuth()
            },
            onDismiss = {
                showDrivePermission = false
                pendingAcceptAfterRelay = null
            }
        )
    }

    val targetToDelete = noteToDelete
    if (targetToDelete != null) {
        val deleteKind = targetToDelete.bulletinDeleteContentKind()
        AlertDialog(
            onDismissRequest = { noteToDelete = null },
            title = { Text(deleteKind.dialogTitle) },
            text = {
                Text(
                    stringRes("delete_entry_scope", deleteKind.entryLabel)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = targetToDelete
                        noteToDelete = null
                        if (target.hasBulletinBinaryAttachment()) {
                            pendingRemotePurgeDelete = target
                        } else {
                            scope.launch {
                                FileApexServices.noteRepository.deleteNoteFromAllDevices(target.noteId)
                            }
                        }
                    }
                ) {
                    Text(stringRes("all_devices_title"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val target = targetToDelete
                            noteToDelete = null
                            scope.launch {
                                FileApexServices.noteRepository.deleteNote(target.noteId)
                            }
                        }
                    ) {
                        Text(stringRes("this_device_only"))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { noteToDelete = null }) {
                        Text(stringRes("cancel"))
                    }
                }
            }
        )
    }

    val remotePurgeDeleteTarget = pendingRemotePurgeDelete
    if (remotePurgeDeleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingRemotePurgeDelete = null },
            title = { Text(stringRes("remove_from_remote")) },
            text = {
                Text(stringRes("also_remove_remote"))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = remotePurgeDeleteTarget
                        pendingRemotePurgeDelete = null
                        scope.launch {
                            FileApexServices.noteRepository.deleteNoteFromAllDevices(
                                noteId = target.noteId,
                                remotePurge = true
                            )
                        }
                    }
                ) {
                    Text(stringRes("remove_file"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            val target = remotePurgeDeleteTarget
                            pendingRemotePurgeDelete = null
                            scope.launch {
                                FileApexServices.noteRepository.deleteNoteFromAllDevices(
                                    noteId = target.noteId,
                                    remotePurge = false
                                )
                            }
                        }
                    ) {
                        Text(stringRes("keep_file"))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    TextButton(onClick = { pendingRemotePurgeDelete = null }) {
                        Text(stringRes("cancel"))
                    }
                }
            }
        )
    }

    val incomingRemotePurgePrompt = remotePurgePrompt
    if (incomingRemotePurgePrompt != null) {
        AlertDialog(
            onDismissRequest = {
                BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                    deleteFiles = false,
                    localPath = incomingRemotePurgePrompt.localPath
                )
                remotePurgePrompt = null
            },
            title = { Text(stringRes("remote_delete_request")) },
            text = {
                Text(
                    stringRes("remote_delete_prompt_body", incomingRemotePurgePrompt.fileName)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                            deleteFiles = true,
                            localPath = incomingRemotePurgePrompt.localPath
                        )
                        remotePurgePrompt = null
                    }
                ) {
                    Text(stringRes("delete_files"), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        BulletinRemoteFilePurgeHandler.resolveFirstTimePrompt(
                            deleteFiles = false,
                            localPath = incomingRemotePurgePrompt.localPath
                        )
                        remotePurgePrompt = null
                    }
                ) {
                    Text(stringRes("keep_files"))
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteBubbleItem(
    item: NoteRecord,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    isCustomGlass: Boolean,
    revealed: Boolean,
    highlighted: Boolean = false,
    assembling: Boolean,
    thumbnail: ImageBitmap?,
    isDownloadingAttachment: Boolean = false,
    footerLabel: String? = null,
    gesturesEnabled: Boolean = true,
    onBubblePositioned: (LayoutCoordinates) -> Unit,
    onRevealedChange: (Boolean) -> Unit,
    onCloseAnyReveal: () -> Unit,
    onDeleteClick: () -> Unit,
    onLockClick: () -> Unit,
    onOpenAttachment: () -> Unit
) {
    val isMine = item.isMine
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val bubbleShape = if (isMine) {
        RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
    }
    val bubbleBg = if (isMine) {
        if (isCustomGlass) Color(0x4400E676) else FileApexTeal.copy(alpha = 0.16f)
    } else {
        cardBg
    }
    val stamp = TimeUtils.noteListStamp(item.epochMs)
    val attachmentName = item.attachmentFileName?.ifBlank { null }
    val hasAttachment = !attachmentName.isNullOrBlank()
    val caption = item.content.trim().takeIf { text ->
        text.isNotEmpty() && text != attachmentName && !text.startsWith("[Synced note from Drive:")
    }

    val density = LocalDensity.current
    val bubbleScope = rememberCoroutineScope()
    val actionCount = if (hasAttachment) 2 else 1
    val actionWidthPx = with(density) { (52.dp * actionCount).toPx() }
    val offsetAnim = remember { Animatable(0f) }
    val showActions = revealed || offsetAnim.value < -1f

    LaunchedEffect(revealed, actionWidthPx) {
        offsetAnim.animateTo(if (revealed) -actionWidthPx else 0f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (showActions) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAttachment) {
                    NoteRevealAction(
                        icon = if (item.attachmentPinned) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (item.attachmentPinned) {
                            stringRes("unlock_attachment")
                        } else {
                            stringRes("lock_attachment")
                        },
                        containerColor = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
                        onClick = onLockClick
                    )
                }
                NoteRevealAction(
                    icon = Icons.Filled.DeleteOutline,
                    contentDescription = stringRes("delete_note"),
                    containerColor = MaterialTheme.colorScheme.error,
                    onClick = onDeleteClick
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (assembling) 0f else 1f },
            horizontalAlignment = alignment
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 380.dp)
                    .offset { IntOffset(offsetAnim.value.roundToInt(), 0) }
                    .then(
                        if (gesturesEnabled) {
                            Modifier
                                .draggable(
                                    state = rememberDraggableState { delta ->
                                        val next = (offsetAnim.value + delta).coerceIn(-actionWidthPx, 0f)
                                        bubbleScope.launch { offsetAnim.snapTo(next) }
                                    },
                                    orientation = Orientation.Horizontal,
                                    onDragStopped = {
                                        val open = offsetAnim.value < -actionWidthPx / 2f
                                        onRevealedChange(open)
                                        bubbleScope.launch {
                                            offsetAnim.animateTo(if (open) -actionWidthPx else 0f)
                                        }
                                    }
                                )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Card(
                    modifier = Modifier.onGloballyPositioned(onBubblePositioned),
                    colors = CardDefaults.cardColors(containerColor = bubbleBg),
                    shape = bubbleShape,
                    border = when {
                        highlighted -> BorderStroke(
                            2.dp,
                            if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                        )
                        isCustomGlass -> BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                        else -> null
                    }
                ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = if (gesturesEnabled) {
                            Modifier.combinedClickable(
                                onClick = {
                                    if (revealed) {
                                        onRevealedChange(false)
                                    } else {
                                        onCloseAnyReveal()
                                    }
                                },
                                onLongClick = { onRevealedChange(true) }
                            )
                        } else {
                            Modifier
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isMine) stringRes("this_device") else item.sourceDeviceName,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        )
                        if (!item.driveFileId.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0x3300E5FF)
                            ) {
                                Text(
                                    text = stringRes("drive_sync"),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                    color = Color(0xFF00E5FF),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    if (!caption.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        NoteLinkText(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                            color = textColor,
                            linkColor = if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                        )
                    }
                    if (attachmentName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (thumbnail != null) {
                            Surface(
                                onClick = {
                                    if (gesturesEnabled) {
                                        onCloseAnyReveal()
                                        onOpenAttachment()
                                    }
                                },
                                enabled = gesturesEnabled,
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCustomGlass) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Image(
                                        bitmap = thumbnail,
                                        contentDescription = stringRes("open_image"),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 220.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = attachmentName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = textColor,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        } else {
                            Surface(
                                onClick = {
                                    if (gesturesEnabled) {
                                        onCloseAnyReveal()
                                        onOpenAttachment()
                                    }
                                },
                                enabled = gesturesEnabled,
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCustomGlass) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ExplorerEntryIcons.iconForFile(attachmentName, ""),
                                        contentDescription = stringRes("open_attachment"),
                                        tint = subTextColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = attachmentName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = textColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val footerText = footerLabel ?: stamp.timeLabel
            if (footerText.isNotEmpty() || isDownloadingAttachment) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (footerText.isNotEmpty()) {
                        Text(
                            text = footerText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = subTextColor
                        )
                    }
                    if (isDownloadingAttachment) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        )
                        Text(
                            text = stringRes("downloading"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = subTextColor
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun NoteRevealAction(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(44.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private sealed class NotesListRow {
    data class DayHeader(val dayKey: String, val label: String) : NotesListRow()
    data class Bubble(val note: NoteRecord) : NotesListRow()
}

private class PendingNotesTransport(
    val sourceRect: Rect,
    val bitmap: ImageBitmap?,
    val isImage: Boolean,
    val accent: Color,
    val icon: ImageVector,
    val text: String,
    val caption: String,
    val path: String,
    val name: String?,
    val size: Long,
    val cardWidthPx: Float,
    val cardHeightPx: Float
)

private fun pickedFileFromDroppedPath(path: String): PickedLocalFile? {
    val cleaned = path.removePrefix("file:").removePrefix("//")
    val filePath = Path(cleaned)
    val meta = SystemFileSystem.metadataOrNull(filePath) ?: return null
    if (meta.isDirectory) return null
    val name = cleaned.substringAfterLast('/').substringAfterLast('\\')
    if (name.isBlank()) return null
    return PickedLocalFile(
        displayName = name,
        sizeBytes = meta.size.coerceAtLeast(0L),
        absolutePath = cleaned
    )
}

private fun outgoingPlaceholderNote(
    fileName: String?,
    caption: String,
    path: String?
): NoteRecord {
    return NoteRecord(
        noteId = "notes-outgoing-placeholder",
        sourceDeviceId = "",
        sourceDeviceName = AppI18n.t("this_device"),
        content = caption,
        epochMs = TimeUtils.now(),
        isMine = true,
        attachmentFileName = fileName,
        attachmentLocalPath = path
    )
}

private fun notesListRows(notes: List<NoteRecord>): List<NotesListRow> {
    val rows = mutableListOf<NotesListRow>()
    var lastDay: String? = null
    for (note in notes) {
        val stamp = TimeUtils.noteListStamp(note.epochMs)
        if (stamp.dayKey != lastDay) {
            rows += NotesListRow.DayHeader(stamp.dayKey, stamp.dateHeader)
            lastDay = stamp.dayKey
        }
        rows += NotesListRow.Bubble(note)
    }
    return rows
}

private val NOTES_COMPOSER_HEIGHT = 47.6.dp
