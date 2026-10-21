package com.fileapex.ui.notes

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.data.settings.AppTheme
import com.fileapex.presentation.NotesListRow
import com.fileapex.presentation.NotesUiState
import com.fileapex.presentation.NotesViewModel
import kotlinx.coroutines.CoroutineScope

internal class NotesFrame(
    val onBack: () -> Unit,
    val focusNoteId: String?,
    val onFocusNoteConsumed: () -> Unit,
    val viewModel: NotesViewModel,
    val state: NotesUiState,
    val currentTheme: AppTheme,
    val isCustomGlass: Boolean,
    val scope: CoroutineScope,
    val density: Density,
    val listState: LazyListState,
    val visualRows: List<NotesListRow>,
    val holdingTransportSlot: Boolean,
    val backgroundColor: Color,
    val cardBg: Color,
    val textColor: Color,
    val subTextColor: Color,
    val launchDriveAuth: () -> Unit,
    val launchSignIn: () -> Unit,
    val pickAttachment: () -> Unit
) {
    val displayNotes get() = state.rawNotes
    val downloadingAttachmentIds get() = state.downloadingAttachmentIds
    val bulletinBoardStyle get() = state.bulletinBoardStyle
    val activeFilter get() = state.activeFilter
    val hasLinks get() = state.hasLinks
    val hasImages get() = state.hasImages
    val hasDocs get() = state.hasDocs
    val hasSnippets get() = state.hasSnippets
    val hasOtherFiles get() = state.hasOtherFiles
    val hasPinned get() = state.hasPinned
    val availableTags get() = state.availableTags
}
