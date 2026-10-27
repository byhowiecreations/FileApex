package com.fileapex.ui.notes

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.fileapex.data.bulletin.BulletinRemoteFilePurgeCoordinator
import com.fileapex.presentation.NotesListRow
import com.fileapex.ui.NOTES_SENT_HOLD_MS
import com.fileapex.ui.NOTES_SENT_LABEL
import com.fileapex.ui.loadNotesAttachmentBitmap
import com.fileapex.ui.loadNotesInlinePreviewBitmap
import com.fileapex.ui.notesAttachmentIsImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import com.fileapex.di.FileApexServices

@Composable
internal fun NotesScope.NotesEffects() {
    with(frame) { with(live) {
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
        FileApexServices.bulletinUnread.value?.markSeen(com.fileapex.data.bulletin.BulletinUnread.latestStamp(FileApexServices.bulletinBoardRepository.messages.first()))
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

    LaunchedEffect(visualRows.size, pendingTransport != null, transport?.settled, transport?.streamDone, focusNoteId) {
        scrollNotesToLatest()
    }

    LaunchedEffect(focusNoteId, visualRows) {
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

    LaunchedEffect(transport == null, dropQueue.size) {
        if (transport != null || pendingTransport != null) return@LaunchedEffect
        val next = dropQueue.removeFirstOrNull() ?: return@LaunchedEffect
        startDroppedAttachment(next, lastDropRootOffset)
    }

    } }
}
