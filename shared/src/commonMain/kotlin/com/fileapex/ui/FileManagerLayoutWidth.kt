package com.fileapex.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The width a shell should pick its layout class from: the window's real width, or its target while the desktop window moves. */
@Composable
fun rememberLayoutWidth(actual: Dp): Dp {
    val target by FileManagerPresence.windowTargetWidthDp.collectAsState()
    return target?.dp ?: actual
}
