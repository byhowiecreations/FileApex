package com.fileapex.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
actual fun rememberDownloadsFilePicker(
    onPicked: (PickedLocalFile?) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    return remember {
        {
            scope.launch {
                val picked = withContext(Dispatchers.IO) {
                    pickFromDownloads()
                }
                onPicked(picked)
            }
        }
    }
}

private fun pickFromDownloads(): PickedLocalFile? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
    val dialog = FileDialog(owner, "Attach file", FileDialog.LOAD)
    val downloads = File(System.getProperty("user.home") ?: ".", "Downloads")
    if (downloads.isDirectory) {
        dialog.directory = downloads.absolutePath
    }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val name = dialog.file ?: return null
    val file = File(dir, name)
    if (!file.isFile) return null
    return PickedLocalFile(
        displayName = file.name,
        sizeBytes = file.length(),
        absolutePath = file.absolutePath
    )
}
