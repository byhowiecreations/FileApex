package com.fileapex.ui.notes

import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.fileapex.platform.PickedLocalFile
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal class PendingNotesTransport(
    val sourceRect: Rect,
    val bitmap: ImageBitmap?,
    val isImage: Boolean,
    val accent: Color,
    val icon: ImageVector,
    val text: String,
    val caption: String,
    val path: String,
    val name: String?,
    val size: Long,
    val cardWidthPx: Float,
    val cardHeightPx: Float
)

internal fun pickedFileFromDroppedPath(path: String): PickedLocalFile? {
    val cleaned = decodeDroppedFileUriPath(path)
    val filePath = Path(cleaned)
    val meta = SystemFileSystem.metadataOrNull(filePath) ?: return null
    if (meta.isDirectory) return null
    val name = cleaned.substringAfterLast('/').substringAfterLast('\\')
    if (name.isBlank()) return null
    return PickedLocalFile(
        displayName = name,
        sizeBytes = meta.size.coerceAtLeast(0L),
        absolutePath = cleaned
    )
}

private fun decodeDroppedFileUriPath(raw: String): String {
    val withoutScheme = raw.trim()
        .removePrefix("file://localhost")
        .removePrefix("file://")
        .removePrefix("file:")
    if (!withoutScheme.contains('%')) return withoutScheme
    val bytes = ArrayList<Byte>(withoutScheme.length)
    var index = 0
    while (index < withoutScheme.length) {
        val ch = withoutScheme[index]
        if (ch == '%' && index + 2 < withoutScheme.length) {
            val hex = withoutScheme.substring(index + 1, index + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.add(hex.toByte())
                index += 3
                continue
            }
        }
        bytes.addAll(ch.toString().encodeToByteArray().toList())
        index += 1
    }
    return bytes.toByteArray().decodeToString()
}

internal val NOTES_COMPOSER_HEIGHT = 47.6.dp

internal val NOTES_COMPOSER_MAX_HEIGHT = 140.dp
