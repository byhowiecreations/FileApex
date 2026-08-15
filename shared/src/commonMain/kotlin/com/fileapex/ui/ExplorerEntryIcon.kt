package com.fileapex.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.ui.theme.FileApexTeal

object ExplorerEntryIcons {
    fun iconFor(item: RemoteFileItem): ImageVector = when {
        item.isDirectory -> Icons.Filled.Folder
        else -> iconForFile(name = item.name, mimeType = item.mimeType)
    }

    fun iconForFile(name: String, mimeType: String): ImageVector {
        val lowerName = name.lowercase()
        val lowerMime = mimeType.lowercase()
        return when {
            lowerMime.startsWith("image/") ||
                lowerName.endsWith(".png") ||
                lowerName.endsWith(".jpg") ||
                lowerName.endsWith(".jpeg") ||
                lowerName.endsWith(".webp") ||
                lowerName.endsWith(".gif") ||
                lowerName.endsWith(".heic") -> Icons.Filled.Image
            lowerMime.startsWith("video/") ||
                lowerName.endsWith(".mp4") ||
                lowerName.endsWith(".mkv") ||
                lowerName.endsWith(".mov") ||
                lowerName.endsWith(".webm") -> Icons.Filled.VideoFile
            lowerMime.startsWith("audio/") ||
                lowerName.endsWith(".mp3") ||
                lowerName.endsWith(".wav") ||
                lowerName.endsWith(".flac") ||
                lowerName.endsWith(".m4a") -> Icons.Filled.AudioFile
            lowerMime.contains("pdf") || lowerName.endsWith(".pdf") -> Icons.Filled.PictureAsPdf
            lowerMime.contains("spreadsheet") ||
                lowerMime.contains("excel") ||
                lowerName.endsWith(".xlsx") ||
                lowerName.endsWith(".xls") ||
                lowerName.endsWith(".csv") -> Icons.Filled.TableChart
            lowerMime.contains("zip") ||
                lowerMime.contains("archive") ||
                lowerName.endsWith(".zip") ||
                lowerName.endsWith(".rar") ||
                lowerName.endsWith(".7z") ||
                lowerName.endsWith(".tar") ||
                lowerName.endsWith(".gz") -> Icons.Filled.Archive
            lowerMime.startsWith("text/") ||
                lowerName.endsWith(".txt") ||
                lowerName.endsWith(".md") ||
                lowerName.endsWith(".log") -> Icons.Filled.Description
            lowerName.endsWith(".kt") ||
                lowerName.endsWith(".java") ||
                lowerName.endsWith(".js") ||
                lowerName.endsWith(".ts") ||
                lowerName.endsWith(".py") ||
                lowerName.endsWith(".json") ||
                lowerName.endsWith(".xml") ||
                lowerName.endsWith(".html") ||
                lowerName.endsWith(".css") -> Icons.Filled.Code
            else -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
    }
}

@Composable
fun ExplorerEntryIcon(
    item: RemoteFileItem,
    modifier: Modifier = Modifier,
    tintFolder: Boolean = true
) {
    val vector = ExplorerEntryIcons.iconFor(item)
    val tint = when {
        item.isDirectory && tintFolder -> FileApexTeal
        else -> MaterialTheme.colorScheme.primary
    }
    Icon(
        imageVector = vector,
        contentDescription = null,
        modifier = modifier,
        tint = tint
    )
}
