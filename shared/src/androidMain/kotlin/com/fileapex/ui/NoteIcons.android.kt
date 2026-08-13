package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import com.fileapex.shared.R

@Composable
actual fun rememberNoteIconPainter(kind: NoteIconKind): Painter {
    val resId = when (kind) {
        NoteIconKind.BLACK -> R.drawable.note_black
        NoteIconKind.WHITE -> R.drawable.note_white
        NoteIconKind.GREEN -> R.drawable.note_green
    }
    return painterResource(id = resId)
}
