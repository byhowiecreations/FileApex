package com.fileapex.ui.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BriefToast
import com.fileapex.platform.openLocalFile
import com.fileapex.presentation.BulletinFilter
import com.fileapex.presentation.NotesListRow
import com.fileapex.ui.ExplorerEntryIcons
import com.fileapex.ui.NOTES_SENDING_LABEL
import com.fileapex.ui.rectIn
import androidx.compose.ui.geometry.Rect
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.launch

@Composable
internal fun ColumnScope.NotesListSection(notes: NotesScope) {
    val notesSnackbar = LocalNotesSnackbar.current
    with(notes) { with(frame) { with(live) {
        if (visualRows.isEmpty() && !holdingTransportSlot) {
            NotesEmptyState(Modifier.weight(1f))
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
                        val outgoingNote = assemblingNote ?: viewModel.createOutgoingPlaceholder(
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
                            style = bulletinBoardStyle,
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
                        NotesListRow.NewDivider -> "new-divider"
                    }
                }) { row ->
                    when (row) {
                        NotesListRow.NewDivider -> NewPostsDivider(isCustomGlass)
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
                                style = bulletinBoardStyle,
                                onBubblePositioned = { coords ->
                                    overlayCoords?.let { overlay ->
                                        val bubble = coords.rectIn(overlay)
                                        bubbleRects[row.note.noteId] = Rect(bubble.left, bubble.top, listRect?.right ?: bubble.right, bubble.bottom)
                                    }
                                },
                                onRevealedChange = { open ->
                                    revealedNoteId = if (open) row.note.noteId else null
                                },
                                onCloseAnyReveal = { revealedNoteId = null },
                                onDeleteClick = {
                                    revealedNoteId = null
                                    if (row.note.attachmentPinned) {
                                        notesSnackbar.say(AppI18n.t("note_locked_unlock_first"))
                                    } else {
                                        noteToDelete = row.note
                                    }
                                },
                                checklist = ChecklistActions(
                                    toggle = viewModel::setChecklistItemDone,
                                    remove = viewModel::removeChecklistItem,
                                    add = { viewModel.addChecklistItem(row.note.noteId, it) }
                                ),
                                onLockClick = {
                                    revealedNoteId = null
                                    viewModel.setAttachmentPinned(
                                        row.note.noteId,
                                        !row.note.attachmentPinned
                                    )
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
                                            notesSnackbar.say(AppI18n.t("could_not_download_attachment"))
                                        }
                                    }
                                },
                                onHashtagClick = { tag -> viewModel.setFilter(BulletinFilter.Tag(tag)) }
                            )
                        }
                    }
                }
            }
        }

    } } }
}

@Composable
internal fun NotesScope.NotesAttachmentChip() {
    with(frame) { with(live) {
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

    } }
}
