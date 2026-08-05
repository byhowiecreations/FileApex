package com.fileapex.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.ui.theme.fileApexChromeContentColor

/**
 * Header affordance for deferred transfers — visible when the queue is non-empty.
 * Tap to open the queue screen and remove items; add via device card drop or multi-select send.
 */
@Composable
fun QueuedFilesButton(
    onClick: () -> Unit,
    iconTint: Color = fileApexChromeContentColor(),
    modifier: Modifier = Modifier
) {
    val count by FileApexServices.transferQueue.pendingCount.collectAsState(initial = 0)
    if (count <= 0) return

    BadgedBox(
        modifier = modifier,
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
                tint = iconTint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
