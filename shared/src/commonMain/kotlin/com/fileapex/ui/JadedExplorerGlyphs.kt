package com.fileapex.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.domain.model.RemoteFileItem
import kotlin.math.min

internal enum class JadedFileKind {
    TEXT,
    ARCHIVE,
    CODE,
    DISK,
    IMAGE,
    PDF,
    MEDIA,
    OTHER
}

private val jadedDiskExtensions = setOf("dmg", "iso", "img", "vhd", "vhdx")
private val jadedArchiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2")
private val jadedCodeExtensions = setOf(
    "json", "sh", "js", "jsx", "ts", "tsx", "kt", "kts", "py", "java", "xml", "html", "css"
)
private val jadedTextExtensions = setOf("md", "txt", "log", "markdown")
private val jadedImageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "heic")
private val jadedMediaExtensions = setOf("mp4", "mkv", "mov", "webm", "mp3", "wav", "flac", "m4a")

internal fun jadedFileKind(name: String, mimeType: String): JadedFileKind {
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    val ext = lowerName.substringAfterLast('.', "")
    return when {
        ext in jadedDiskExtensions -> JadedFileKind.DISK
        lowerMime.contains("pdf") || ext == "pdf" -> JadedFileKind.PDF
        lowerMime.contains("zip") || lowerMime.contains("archive") || ext in jadedArchiveExtensions ->
            JadedFileKind.ARCHIVE
        lowerMime.startsWith("image/") || ext in jadedImageExtensions -> JadedFileKind.IMAGE
        lowerMime.startsWith("video/") || lowerMime.startsWith("audio/") || ext in jadedMediaExtensions ->
            JadedFileKind.MEDIA
        lowerMime.startsWith("text/") || ext in jadedTextExtensions -> JadedFileKind.TEXT
        ext in jadedCodeExtensions -> JadedFileKind.CODE
        else -> JadedFileKind.OTHER
    }
}

internal fun jadedPillColor(kind: JadedFileKind): Color = when (kind) {
    JadedFileKind.TEXT -> Color(0xFFFFB74D)
    JadedFileKind.ARCHIVE -> Color(0xFFE85D4C)
    JadedFileKind.CODE -> Color(0xFF4FC3F7)
    JadedFileKind.DISK -> Color(0xFF9AA4AE)
    JadedFileKind.IMAGE -> Color(0xFF80CBC4)
    JadedFileKind.PDF -> Color(0xFFEF5350)
    JadedFileKind.MEDIA -> Color(0xFFCE93D8)
    JadedFileKind.OTHER -> Color(0xFF8B9AA3)
}

internal fun jadedExtensionLabel(name: String): String {
    val ext = name.substringAfterLast('.', "").trim()
    if (ext.isEmpty() || ext.length == name.length) return "FILE"
    return ext.take(4).uppercase()
}

private const val JadedFolderPath =
    "M14 8H33C36 8 38 9 40 11L45 16H82C87 16 90 19 90 24V62C90 67 87 72 82 72H14C9 72 6 67 6 62V18C6 12 9 8 14 8Z"

@Composable
internal fun JadedFolderGlyph(name: String, modifier: Modifier = Modifier) {
    val folder = remember { PathParser().parsePathString(JadedFolderPath).toPath() }
    Canvas(modifier.semantics { contentDescription = name }) {
        val scale = min(size.width / 96f, size.height / 80f)
        val dx = (size.width - 96f * scale) / 2f
        val dy = (size.height - 80f * scale) / 2f
        withTransform({
            translate(dx, dy)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            drawPath(
                path = folder,
                brush = Brush.verticalGradient(
                    0f to Color(0xFF5B84A6),
                    1f to Color(0xFF3E5E7C),
                    startY = 0f,
                    endY = 80f
                )
            )
            drawPath(
                path = folder,
                color = Color(0xFF8CC8F5),
                style = Stroke(width = 3f, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
internal fun JadedFileGlyph(item: RemoteFileItem, modifier: Modifier = Modifier) {
    val kind = jadedFileKind(item.name, item.mimeType)
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val shell = ext == "sh"
    val markdown = ext == "md"
    val tint = when {
        shell || markdown -> Color(0xFFFFB74D)
        else -> jadedPillColor(kind)
    }
    val icon = if (markdown) Icons.Filled.Description else jadedFileIcon(kind)
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (markdown) Color(0xFFE8B86D) else tint,
            modifier = Modifier.size(32.dp)
        )
        if (markdown) {
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = null,
                tint = Color(0xFFFFB74D),
                modifier = Modifier.size(14.dp)
            )
        }
        if (shell || (kind == JadedFileKind.CODE && !markdown)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (shell) Color(0xFFFFB74D) else Color(0xFFFFB74D))
            )
        }
    }
}

@Composable
internal fun JadedCardCornerBadge(item: RemoteFileItem, modifier: Modifier = Modifier) {
    val kind = jadedFileKind(item.name, item.mimeType)
    val ext = item.name.substringAfterLast('.', "").lowercase()
    val label = when {
        kind == JadedFileKind.ARCHIVE -> jadedExtensionLabel(item.name).lowercase()
        ext == "md" -> ".md"
        else -> return
    }
    val badge = if (kind == JadedFileKind.ARCHIVE) Color(0xFFE85D4C) else Color(0xFFFFB74D)
    Text(
        text = label,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(badge)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

private fun jadedFileIcon(kind: JadedFileKind): ImageVector = when (kind) {
    JadedFileKind.TEXT -> Icons.Filled.Description
    JadedFileKind.ARCHIVE -> Icons.Filled.Archive
    JadedFileKind.CODE -> Icons.Filled.Code
    JadedFileKind.DISK -> Icons.Filled.Album
    JadedFileKind.IMAGE -> Icons.Filled.Image
    JadedFileKind.PDF -> Icons.Filled.PictureAsPdf
    JadedFileKind.MEDIA -> Icons.Filled.VideoFile
    JadedFileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun jadedBadgeLabel(name: String, kind: JadedFileKind): String? = when (kind) {
    JadedFileKind.ARCHIVE, JadedFileKind.TEXT, JadedFileKind.PDF, JadedFileKind.DISK ->
        jadedExtensionLabel(name)
    else -> null
}
