package com.fileapex.ui.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.LayoutCoordinates
import com.fileapex.cloud.drive.NotesAttachmentDecision
import com.fileapex.data.bulletin.BulletinRemotePurgePrompt
import com.fileapex.data.note.NoteRecord
import com.fileapex.platform.PickedLocalFile
import com.fileapex.ui.NotesAttachmentTransportState

internal class NotesLive {
    var inputContent by mutableStateOf("")
    var noteToDelete by mutableStateOf<NoteRecord?>(null)
    var pendingRemotePurgeDelete by mutableStateOf<NoteRecord?>(null)
    var remotePurgePrompt by mutableStateOf<BulletinRemotePurgePrompt?>(null)
    var showNotesPermissionPrompt by mutableStateOf(false)
    var pendingNoteToSend by mutableStateOf<String?>(null)
    var attachError by mutableStateOf<String?>(null)
    var pendingAttachmentName by mutableStateOf<String?>(null)
    var pendingAttachmentPath by mutableStateOf<String?>(null)
    var pendingAttachmentSize by mutableLongStateOf(0L)
    var revealedNoteId by mutableStateOf<String?>(null)
    var highlightedNoteId by mutableStateOf<String?>(null)
    var relayOptIn by mutableStateOf<NotesAttachmentDecision.OfferRelayOptIn?>(null)
    var pendingRelayPick by mutableStateOf<PickedLocalFile?>(null)
    var showDrivePermission by mutableStateOf(false)
    var pendingAcceptAfterRelay by mutableStateOf<PickedLocalFile?>(null)
    var attachedPreview by mutableStateOf<ImageBitmap?>(null)
    var overlayCoords by mutableStateOf<LayoutCoordinates?>(null)
    var attachedChipRect by mutableStateOf<Rect?>(null)
    var listRect by mutableStateOf<Rect?>(null)
    var transport by mutableStateOf<NotesAttachmentTransportState?>(null)
    var pendingTransport by mutableStateOf<PendingNotesTransport?>(null)
    var departingChipName by mutableStateOf<String?>(null)
    var departingChipPreview by mutableStateOf<ImageBitmap?>(null)
    var dropHover by mutableStateOf(false)
    var lastDropRootOffset by mutableStateOf<Offset?>(null)
    val dropQueue = mutableStateListOf<PickedLocalFile>()
    val bubbleThumbs = mutableStateMapOf<String, ImageBitmap?>()
    var filterMenuExpanded by mutableStateOf(false)
}
