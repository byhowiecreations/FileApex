package com.fileapex.ui.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.PlatformClipboard
import com.fileapex.ui.theme.FileApexTeal
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.fileapex.i18n.stringRes

/** The Bulletin Board's own message line: shown inside the app, not as a system toast. */
class NotesSnackbar(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun show(message: String) {
        scope.launch {
            host.currentSnackbarData?.dismiss()
            host.showSnackbar(message)
        }
    }
}

val LocalNotesSnackbar = staticCompositionLocalOf<NotesSnackbar?> { null }

enum class NoteMenuAction { Copy, Lock, Unlock, Delete }

object NoteMenuActions {
    fun forNote(hasCopyable: Boolean, locked: Boolean): List<NoteMenuAction> = buildList {
        if (hasCopyable) add(NoteMenuAction.Copy)
        add(if (locked) NoteMenuAction.Unlock else NoteMenuAction.Lock)
        add(NoteMenuAction.Delete)
    }
}

/** Calls [onRightClick] when the secondary mouse button is released on this element; every other press passes through. */
fun Modifier.onRightClick(enabled: Boolean, onRightClick: () -> Unit): Modifier =
    if (!enabled) this else pointerInput(onRightClick) {
        awaitEachGesture {
            var event = awaitPointerEvent(PointerEventPass.Main)
            while (event.changes.none { it.changedToDown() }) event = awaitPointerEvent(PointerEventPass.Main)
            if (!event.buttons.isSecondaryPressed) return@awaitEachGesture
            event.changes.forEach { it.consume() }
            waitForUpOrCancellation()?.let {
                it.consume()
                onRightClick()
            }
        }
    }

@Composable
internal fun NoteContextMenu(
    expanded: Boolean,
    actions: List<NoteMenuAction>,
    onDismiss: () -> Unit,
    onAction: (NoteMenuAction) -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        for (action in actions) {
            val (label, icon) = when (action) {
                NoteMenuAction.Copy -> "copy_to_clipboard" to Icons.Filled.ContentCopy
                NoteMenuAction.Lock -> "lock_attachment" to Icons.Filled.Lock
                NoteMenuAction.Unlock -> "unlock_attachment" to Icons.Filled.LockOpen
                NoteMenuAction.Delete -> "delete_note" to Icons.Filled.DeleteOutline
            }
            DropdownMenuItem(
                text = { Text(stringRes(label)) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onAction(action)
                }
            )
        }
    }
}

@Composable
internal fun NoteRevealActions(
    copyableText: String?,
    locked: Boolean,
    isCustomGlass: Boolean,
    onCloseAnyReveal: () -> Unit,
    onLockClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbar = LocalNotesSnackbar.current
    Row(
        modifier = modifier.padding(end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (copyableText != null) {
            NoteRevealAction(
                icon = Icons.Filled.ContentCopy,
                contentDescription = stringRes("copy_to_clipboard"),
                containerColor = if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal,
                onClick = {
                    PlatformClipboard.setSystemClipboardText(copyableText)
                    snackbar.say(AppI18n.t("copied_to_clipboard"))
                    onCloseAnyReveal()
                }
            )
        }
        NoteRevealAction(
            icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = stringRes(if (locked) "unlock_attachment" else "lock_attachment"),
            containerColor = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal,
            onClick = onLockClick
        )
        NoteRevealAction(
            icon = Icons.Filled.DeleteOutline,
            contentDescription = stringRes("delete_note"),
            containerColor = MaterialTheme.colorScheme.error,
            onClick = onDeleteClick
        )
    }
}


/** The in-app line when the Bulletin Board is showing; the system toast only as a fallback elsewhere. */
fun NotesSnackbar?.say(message: String) {
    if (this != null) show(message) else BriefToast.show(message)
}

internal fun runNoteMenuAction(
    action: NoteMenuAction,
    copyableText: String,
    snackbar: NotesSnackbar?,
    onLock: () -> Unit,
    onDelete: () -> Unit
) {
    when (action) {
        NoteMenuAction.Copy -> {
            PlatformClipboard.setSystemClipboardText(copyableText)
            snackbar.say(AppI18n.t("copied_to_clipboard"))
        }
        NoteMenuAction.Lock, NoteMenuAction.Unlock -> onLock()
        NoteMenuAction.Delete -> onDelete()
    }
}

/** A tap closes an open swipe; a long press copies the text and opens the swipe buttons. Anywhere on the post. */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.bubbleTaps(
    enabled: Boolean,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    onCloseAnyReveal: () -> Unit,
    copyableText: String?,
    snackbar: NotesSnackbar?
): Modifier = if (!enabled) this else combinedClickable(
    onClick = { if (revealed) onRevealedChange(false) else onCloseAnyReveal() },
    onLongClick = {
        if (copyableText != null) {
            PlatformClipboard.setSystemClipboardText(copyableText)
            snackbar.say(AppI18n.t("copied_to_clipboard"))
        }
        onRevealedChange(true)
    }
)

/** While a swipe is open, a press anywhere outside the open post and its buttons closes it; the press still reaches what is under it. */
fun Modifier.closeSwipeOnOutsidePress(active: Boolean, openArea: () -> Rect?, onClose: () -> Unit): Modifier =
    if (!active) this else pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val area = openArea()
            if (area == null || !area.contains(down.position)) onClose()
        }
    }
