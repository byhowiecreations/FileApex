package com.fileapex.ui.explorer

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.fileapex.domain.preview.RemoteThumbnailPolicy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.decodeVideoPoster
import com.fileapex.ui.ExplorerEntryIcon
import com.fileapex.ui.JadedFileGlyph
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SelectionIndicator(selected: Boolean) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(
                if (selected) FileApexTeal
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
internal fun ExplorerFileVisual(
    item: RemoteFileItem,
    jaded: Boolean,
    isRemoteTarget: Boolean,
    modifier: Modifier
) {
    val thumb = rememberMediaThumb(item, isRemoteTarget)
    if (thumb != null) {
        Image(
            bitmap = thumb,
            contentDescription = item.name,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
    } else if (jaded) {
        JadedFileGlyph(item = item, modifier = modifier)
    } else {
        ExplorerEntryIcon(item = item, modifier = modifier)
    }
}

@Composable
private fun rememberMediaThumb(item: RemoteFileItem, isRemoteTarget: Boolean): ImageBitmap? {
    if (!RemoteThumbnailPolicy.isMediaCandidate(item)) return null
    val remoteFetcher = LocalRemoteThumbnailFetcher.current
    if (isRemoteTarget && remoteFetcher == null) return null
    var bitmap by remember(item.absolutePath, isRemoteTarget, item.lastModified, item.sizeBytes) {
        mutableStateOf<ImageBitmap?>(null)
    }
    LaunchedEffect(item.absolutePath, isRemoteTarget, item.lastModified, item.sizeBytes, remoteFetcher) {
        bitmap = if (isRemoteTarget) {
            runCatching { remoteFetcher?.invoke(item) }.getOrNull()
        } else {
            val inferredMime = RemoteThumbnailPolicy.inferMimeType(item.name, item.mimeType)
            withContext(Dispatchers.IO) {
                runCatching {
                    if (inferredMime.startsWith("video/")) {
                        decodeVideoPoster(item.absolutePath, 256)
                    } else {
                        decodeLocalImageFile(item.absolutePath, 256)
                    }
                }.getOrNull()
            }
        }
    }
    return bitmap
}

