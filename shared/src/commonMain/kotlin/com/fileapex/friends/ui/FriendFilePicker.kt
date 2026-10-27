package com.fileapex.friends.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fileapex.data.identity.LocalDeviceNameStore
import com.fileapex.data.identity.LocalIdentity
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.BrowseTarget
import com.fileapex.ui.FileExplorerScreen
import java.io.File

/** Where a friend send picks its files: the same file screen the rest of the app uses. Several files can be ticked. */
@Composable
fun rememberFriendFilePicker(
    titleKey: String = "friends_send_file",
    viewModelKey: String = "friend-file-pick",
    onPicked: (List<File>) -> Unit
): () -> Unit {
    var open by remember { mutableStateOf(false) }
    if (open) FriendFilePickerDialog(titleKey, viewModelKey, onPick = { open = false; onPicked(it) }, onDismiss = { open = false })
    return { open = true }
}

@Composable
private fun FriendFilePickerDialog(titleKey: String, viewModelKey: String, onPick: (List<File>) -> Unit, onDismiss: () -> Unit) {
    val target = remember {
        val identity = FileApexServices.localIdentity
        BrowseTarget.Local(
            deviceId = LocalIdentity.LOCAL_DEVICE_ID,
            displayName = AppI18n.t("this_device_named", LocalDeviceNameStore.current()),
            rootPath = identity.rootPath
        )
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The glass themes make the surface see-through, which let the device canvas show behind the file list.
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringRes("cancel")) }
                }
                FileExplorerScreen(
                    target = target,
                    onBack = onDismiss,
                    viewModelKey = viewModelKey,
                    titleOverride = stringRes(titleKey),
                    embeddedInCompactShell = true,
                    showTopBar = true,
                    hostSplit = false,
                    onPickFiles = { paths -> onPick(paths.map { File(it) }.filter { it.isFile }) }
                )
            }
        }
    }
}
