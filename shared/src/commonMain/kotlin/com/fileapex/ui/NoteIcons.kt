package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter

enum class NoteIconKind {
    BLACK,
    WHITE,
    GREEN
}

@Composable
expect fun rememberNoteIconPainter(kind: NoteIconKind): Painter
