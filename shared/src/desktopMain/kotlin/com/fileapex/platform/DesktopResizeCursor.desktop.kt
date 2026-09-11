package com.fileapex.platform

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

actual fun horizontalResizePointerIcon(): PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
