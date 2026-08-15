package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Note
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.fileapex.platform.decodeImageBytes
import java.io.File

@Composable
actual fun rememberNoteIconPainter(kind: NoteIconKind): Painter {
    val bitmap = remember(kind) {
        val path = when (kind) {
            NoteIconKind.BLACK -> "icons/note_black.png"
            NoteIconKind.WHITE -> "icons/note_white.png"
            NoteIconKind.GREEN -> "icons/note_green.png"
        }
        val bytes = loadDesktopResourceBytes(path)
        if (bytes != null) {
            decodeImageBytes(bytes)
        } else {
            null
        }
    }

    if (bitmap != null) {
        return BitmapPainter(bitmap)
    }

    val fallbackVector = Icons.AutoMirrored.Filled.Note
    return rememberVectorPainter(fallbackVector)
}

private fun loadDesktopResourceBytes(relativePath: String): ByteArray? {
    val absPath = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
    val relPath = relativePath.removePrefix("/")

    runCatching {
        NoteIconKind::class.java.getResourceAsStream(absPath)?.use { it.readBytes() }
    }.getOrNull()?.let { return it }

    runCatching {
        Thread.currentThread().contextClassLoader?.getResourceAsStream(relPath)?.use { it.readBytes() }
    }.getOrNull()?.let { return it }

    runCatching {
        ClassLoader.getSystemResourceAsStream(relPath)?.use { it.readBytes() }
    }.getOrNull()?.let { return it }

    // Unpackaged `run` looks next to the project sources.
    val devPaths = listOf(
        "shared/src/desktopMain/resources/$relPath",
        "../shared/src/desktopMain/resources/$relPath",
        "composeApp/src/desktopMain/resources/$relPath",
        "../composeApp/src/desktopMain/resources/$relPath",
        "src/desktopMain/resources/$relPath"
    )

    for (p in devPaths) {
        val file = File(p)
        if (file.exists() && file.isFile) {
            runCatching { file.readBytes() }.getOrNull()?.let { return it }
        }
    }

    return null
}
