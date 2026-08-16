package com.fileapex.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private val pickerBusy = AtomicBoolean(false)

@Composable
actual fun rememberDownloadsFilePicker(
    onPicked: (PickedLocalFile?) -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onPicked)
    return remember(scope) {
        {
            if (pickerBusy.compareAndSet(false, true)) {
                scope.launch {
                    try {
                        yield()
                        val picked = pickAttachmentFile()
                        callback.value(picked)
                        delay(500)
                    } finally {
                        pickerBusy.set(false)
                    }
                }
            }
        }
    }
}

private suspend fun pickAttachmentFile(): PickedLocalFile? {
    val downloads = File(System.getProperty("user.home") ?: ".", "Downloads")
    val startDir = downloads.takeIf { it.isDirectory }?.absolutePath
    val path = if (DesktopPlatformPaths.isMacOs() && DesktopMacTrayBridge.hasNativeOpenPanel()) {
        withContext(Dispatchers.IO) {
            DesktopMacTrayBridge.pickOpenFile("Attach file", startDir)
        }
    } else {
        pickWithAwtFileDialog(startDir)
    } ?: return null
    return withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile) return@withContext null
        PickedLocalFile(
            displayName = file.name,
            sizeBytes = file.length(),
            absolutePath = file.absolutePath
        )
    }
}

private suspend fun pickWithAwtFileDialog(startDir: String?): String? {
    return withContext(Dispatchers.Main) {
        showAwtFileDialog(startDir)
    }
}

private fun showAwtFileDialog(startDir: String?): String? {
    fun show(): String? {
        val owner = currentFrame()
        val dialog = FileDialog(owner, "Attach file", FileDialog.LOAD)
        if (!startDir.isNullOrBlank()) {
            dialog.directory = startDir
        }
        dialog.isMultipleMode = false
        dialog.isVisible = true
        val dir = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val file = File(dir, name)
        return file.takeIf { it.isFile }?.absolutePath
    }
    if (EventQueue.isDispatchThread()) return show()
    var picked: String? = null
    EventQueue.invokeAndWait { picked = show() }
    return picked
}

private fun currentFrame(): Frame? {
    val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
    if (active is Frame) return active
    return Frame.getFrames().firstOrNull { it.isShowing }
}
