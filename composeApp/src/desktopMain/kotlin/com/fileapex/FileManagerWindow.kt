package com.fileapex

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.fileapex.data.settings.AppTheme
import com.fileapex.platform.DesktopUsableScreen
import com.fileapex.platform.DesktopWindowExpansion
import com.fileapex.platform.DesktopWindowResizer
import com.fileapex.platform.WindowFrame
import com.fileapex.ui.FileManagerPresence
import com.fileapex.ui.motion.SystemReducedMotion
import java.awt.Window

private val FileManagerWindowWidth = 1100.dp

private fun WindowState.frameOrNull(): WindowFrame? {
    if (position.x == Dp.Unspecified || position.y == Dp.Unspecified) return null
    return WindowFrame(position.x.value, position.y.value, size.width.value, size.height.value)
}

/**
 * Widens the window while a file manager is open and puts it back to the user's own size and position afterwards.
 * The resizer moves the AWT window itself; [windowState] is written once when the move has settled, and window bounds
 * are only saved to disk while [persistable], so the widened size never replaces the user's.
 */
class FileManagerWindow internal constructor(
    private val windowState: WindowState,
    private val resizer: DesktopWindowResizer
) {
    internal var window by mutableStateOf<Window?>(null)
    private var before by mutableStateOf<WindowFrame?>(null)
    private var expanded by mutableStateOf<WindowFrame?>(null)
    private var returningTo by mutableStateOf<WindowFrame?>(null)
    private var moving by mutableStateOf(false)

    val persistable: Boolean get() = expanded == null && !moving

    /** The bounds to save when the app quits: the user's own ones, not the widened or moving ones. */
    fun boundsToPersist(): Pair<DpSize, WindowPosition> {
        returningTo?.let { return DpSize(it.width.dp, it.height.dp) to WindowPosition(it.x.dp, it.y.dp) }
        val kept = before
        val wide = expanded
        val now = windowState.frameOrNull()
        val userResized = kept == null || wide == null || now == null || (!moving && DesktopWindowExpansion.restore(kept, wide, now) == null)
        if (userResized) return windowState.size to windowState.position
        return DpSize(kept!!.width.dp, kept.height.dp) to WindowPosition(kept.x.dp, kept.y.dp)
    }

    internal fun decide(open: Boolean, theme: AppTheme) {
        val awt = window ?: return
        if (windowState.isMinimized || windowState.placement != WindowPlacement.Floating) return
        val wanted = open && theme != AppTheme.FREESTYLE
        val current = windowState.frameOrNull() ?: return
        val wide = expanded
        val kept = before
        if (wanted && wide == null) {
            val base = returningTo ?: current
            val wider = DesktopWindowExpansion.expand(base, DesktopUsableScreen.forWindow(awt), FileManagerWindowWidth.value) ?: return
            before = base
            expanded = wider
            returningTo = null
            moveTo(awt, wider)
        } else if (!wanted && wide != null && kept != null) {
            val back = if (moving) kept else DesktopWindowExpansion.restore(kept, wide, current)
            expanded = null
            before = null
            if (back != null) {
                returningTo = back
                moveTo(awt, back)
            }
        }
    }

    private fun moveTo(awt: Window, target: WindowFrame) {
        moving = true
        FileManagerPresence.windowTargetWidthDp.value = target.width
        resizer.animateTo(awt, target, SystemReducedMotion.isReduced()) {
            windowState.position = WindowPosition(target.x.dp, target.y.dp)
            windowState.size = DpSize(target.width.dp, target.height.dp)
            if (returningTo == target) returningTo = null
            moving = false
            FileManagerPresence.windowTargetWidthDp.value = null
        }
    }
}

@Composable
fun rememberFileManagerWindow(windowState: WindowState, appTheme: AppTheme?, minimized: Boolean): FileManagerWindow {
    val scope = rememberCoroutineScope()
    val controller = remember(windowState) { FileManagerWindow(windowState, DesktopWindowResizer.create(scope)) }
    val open by FileManagerPresence.settled.collectAsState(initial = false)
    LaunchedEffect(open, appTheme, minimized, controller.window) {
        appTheme?.let { controller.decide(open, it) }
    }
    return controller
}
