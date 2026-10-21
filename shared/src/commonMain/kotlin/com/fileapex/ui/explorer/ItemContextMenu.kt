package com.fileapex.ui.explorer

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.ThemeShapeStyle
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.stringRes
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.FolderPickerDialog
import com.fileapex.ui.LocalExplorerMutations
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.explorerIconFamily
import com.fileapex.ui.formatBytes
import com.fileapex.ui.theme.KineticStyleLook

@Composable
internal fun ItemContextMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    isRemoteTarget: Boolean,
    onCopy: () -> Unit,
    onSendToDevice: () -> Unit,
    onDownload: () -> Unit,
    item: RemoteFileItem? = null,
    onOpen: () -> Unit = {},
    onSelect: () -> Unit = {},
) {
    val mutations = LocalExplorerMutations.current
    var infoOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var moveOpen by remember { mutableStateOf(false) }
    var remoteDeleteOpen by remember { mutableStateOf(false) }
    var renameText by remember(item?.id) { mutableStateOf(TextFieldValue(item?.name.orEmpty())) }
    val traits = LocalAppTheme.current.traits
    val menuShape = when (traits.shapeStyle) {
        ThemeShapeStyle.Pill -> RoundedCornerShape(percent = 50)
        ThemeShapeStyle.RoundedSquare -> RoundedCornerShape(12.dp)
    }
    val family = explorerIconFamily()
    val iconTint = if (traits.orbitalHome && LocalKineticStyle.current == KineticStyle.JADED_STEEL) {
        KineticStyleLook.steel
    } else MaterialTheme.colorScheme.primary
    @Composable
    fun MenuGlyph(icon: ExplorerIcon, tint: Color = iconTint) {
        Icon(explorerIcon(icon, family), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
    val favorites by FileApexServices.settings.explorerFavorites.collectAsState()
    val local = item != null && !isRemoteTarget && mutations != null
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = menuShape
    ) {
        if (item != null) {
            DropdownMenuItem(
                text = { Text(stringRes("open")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Open) },
                onClick = {
                    onDismissRequest()
                    onOpen()
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringRes("copy_action")) },
            leadingIcon = { MenuGlyph(ExplorerIcon.Copy) },
            onClick = {
                onDismissRequest()
                onCopy()
            }
        )
        if (local) {
            DropdownMenuItem(
                text = { Text(stringRes("move_action")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Move) },
                onClick = {
                    onDismissRequest()
                    moveOpen = true
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringRes("send_to")) },
            leadingIcon = { MenuGlyph(ExplorerIcon.SendTo) },
            onClick = {
                onDismissRequest()
                onSendToDevice()
            }
        )
        if (isRemoteTarget) {
            DropdownMenuItem(
                text = { Text(stringRes("download")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Download) },
                onClick = {
                    onDismissRequest()
                    onDownload()
                }
            )
        }
        if (item != null && !isRemoteTarget) {
            val starred = item.absolutePath in favorites
            DropdownMenuItem(
                text = { Text(stringRes(if (starred) "unfavorite" else "favorite")) },
                leadingIcon = { MenuGlyph(if (starred) ExplorerIcon.FavoriteOn else ExplorerIcon.Favorite) },
                onClick = {
                    onDismissRequest()
                    FileApexServices.settings.toggleExplorerFavorite(item.absolutePath)
                }
            )
        }
        if (local) {
            DropdownMenuItem(
                text = { Text(stringRes("rename")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Rename) },
                onClick = {
                    onDismissRequest()
                    // Pre-select the name without its extension so typing replaces just the stem.
                    val stem = item.name.substringBeforeLast('.', item.name).length
                    renameText = TextFieldValue(item.name, TextRange(0, stem))
                    renameOpen = true
                }
            )
            val zip = item.name.endsWith(".zip", ignoreCase = true)
            DropdownMenuItem(
                text = { Text(stringRes(if (zip) "uncompress" else "compress")) },
                leadingIcon = { MenuGlyph(if (zip) ExplorerIcon.Uncompress else ExplorerIcon.Compress) },
                onClick = {
                    onDismissRequest()
                    if (zip) mutations.uncompress(item) else mutations.compress(item)
                }
            )
        }
        if (item != null) {
            DropdownMenuItem(
                text = { Text(stringRes("get_info")) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Info) },
                onClick = {
                    onDismissRequest()
                    infoOpen = true
                }
            )
        }
        if ((local || isRemoteTarget) && item != null && mutations != null) {
            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringRes("delete"), color = MaterialTheme.colorScheme.error) },
                leadingIcon = { MenuGlyph(ExplorerIcon.Delete, MaterialTheme.colorScheme.error) },
                onClick = {
                    onDismissRequest()
                    // Local deletes go to the trash and can be undone; a remote one is confirmed first.
                    if (isRemoteTarget) remoteDeleteOpen = true else mutations.delete(item)
                }
            )
        }
    }
    if (remoteDeleteOpen && item != null && mutations != null) {
        AlertDialog(
            onDismissRequest = { remoteDeleteOpen = false },
            title = { Text(stringRes("remote_delete_confirm_title")) },
            text = { Text(stringRes("remote_delete_confirm_body", item.name)) },
            confirmButton = {
                TextButton(onClick = {
                    remoteDeleteOpen = false
                    mutations.delete(item)
                }) { Text(stringRes("delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { remoteDeleteOpen = false }) { Text(stringRes("cancel")) } }
        )
    }
    if (moveOpen && item != null && mutations != null) {
        FolderPickerDialog(
            startPath = item.absolutePath.substringBeforeLast('/'),
            title = stringRes("move_to_title", item.name),
            confirmLabel = stringRes("move_here"),
            onPick = { destination ->
                moveOpen = false
                mutations.move(item, destination)
            },
            onDismiss = { moveOpen = false }
        )
    }
    if (infoOpen && item != null) {
        AlertDialog(
            onDismissRequest = { infoOpen = false },
            title = { Text(item.name) },
            text = {
                Text(
                    listOf(item.absolutePath, formatBytes(item.sizeBytes), item.mimeType)
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                )
            },
            confirmButton = {
                TextButton(onClick = { infoOpen = false }) { Text(stringRes("close")) }
            }
        )
    }
    if (renameOpen && item != null && mutations != null) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(stringRes("rename")) },
            text = {
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                // Wraps over several lines so a long name is fully visible and every part can be tapped.
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = false,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    mutations.rename(item, renameText.text)
                }) { Text(stringRes("rename")) }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text(stringRes("cancel")) }
            }
        )
    }
}
