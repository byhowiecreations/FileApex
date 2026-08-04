package com.fileapex.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.fileApexChromeContentColor

/**
 * Header affordance for deferred transfers — visible only when the queue is non-empty.
 * Desktop: accepts file/folder drops to add more queued sends.
 */
@Composable
fun QueuedFilesButton(
    onClick: () -> Unit,
    onDesktopFilesDropped: ((List<String>) -> Unit)? = null,
    iconTint: Color = fileApexChromeContentColor(),
    modifier: Modifier = Modifier
) {
    val count by FileApexServices.transferQueue.pendingCount.collectAsState(initial = 0)
    if (count <= 0) return

    var dropHover by remember { mutableStateOf(false) }
    val desktopDrop = usesDesktopFileSelection() && onDesktopFilesDropped != null

    val buttonModifier = modifier.then(
        if (desktopDrop) {
            Modifier.deviceFileDropTarget(
                onHoverChange = { dropHover = it },
                onFilesDropped = { paths -> onDesktopFilesDropped?.invoke(paths) }
            )
        } else {
            Modifier
        }
    )

    BadgedBox(
        modifier = buttonModifier,
        badge = {
            Badge {
                Text(text = if (count > 99) "99+" else count.toString())
            }
        }
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = "Queued files",
                tint = if (dropHover) MaterialTheme.colorScheme.primary else iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
