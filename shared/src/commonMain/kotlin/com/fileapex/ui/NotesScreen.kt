package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fileapex.presentation.NotesViewModel
import com.fileapex.ui.notes.NotesAttachDialogs
import com.fileapex.ui.notes.NotesBody
import com.fileapex.ui.notes.NotesDeleteDialogs
import com.fileapex.ui.notes.NotesEffects
import com.fileapex.ui.notes.rememberNotesScope

@Composable
fun NotesScreen(
    onBack: () -> Unit,
    focusNoteId: String? = null,
    onFocusNoteConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: NotesViewModel = viewModel { NotesViewModel() }
) {
    val notes = rememberNotesScope(onBack, focusNoteId, onFocusNoteConsumed, viewModel)
    notes.NotesEffects()
    notes.NotesBody(modifier)
    notes.NotesAttachDialogs()
    notes.NotesDeleteDialogs()
}
