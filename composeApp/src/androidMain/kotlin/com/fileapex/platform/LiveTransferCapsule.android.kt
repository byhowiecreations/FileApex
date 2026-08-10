package com.fileapex.platform

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text


import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.ui.theme.FileApexTeal
import kotlinx.coroutines.delay

enum class CapsuleState {
    Hidden,
    Pending,
    Transferring,
    Completed,
    Error
}

data class CapsuleDisplayData(
    val state: CapsuleState = CapsuleState.Hidden,
    val title: String = "",
    val subtitle: String = "",
    val progress: Float = 0f,
    val pendingCount: Int = 0
)

@Composable
fun LiveTransferCapsuleOverlay(
    modifier: Modifier = Modifier,
    cutoutPaddingTop: Int = 0,
    isCornerCutout: Boolean = false
) {
    val settings = FileApexServices.settings
    val capsuleEnabled by settings.liveTransferCapsuleEnabled.collectAsState()
    val showQueueEnabled by settings.liveTransferShowQueueEnabled.collectAsState()
    val isTransferActive by TransferActivityGuard.isTransferActiveFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())

    var capsuleData by remember { mutableStateOf(CapsuleDisplayData()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(capsuleEnabled, showQueueEnabled, isTransferActive, pendingItems) {
        if (!capsuleEnabled) {
            capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
            return@LaunchedEffect
        }

        val activeSendingItem = pendingItems.firstOrNull { it.isSending }
        val pendingCount = pendingItems.size

        when {
            isTransferActive || activeSendingItem != null -> {
                val title = activeSendingItem?.displayLabel?.ifBlank { "Transferring files..." } ?: "Streaming files..."
                val subtitle = if (pendingCount > 1) "$pendingCount items in queue" else "Active transfer"
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Transferring,
                    title = title,
                    subtitle = subtitle,
                    progress = 0.5f,
                    pendingCount = pendingCount
                )
            }
            capsuleData.state == CapsuleState.Transferring && !isTransferActive -> {
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Completed,
                    title = "Transfer Complete",
                    subtitle = "All files transmitted",
                    progress = 1f,
                    pendingCount = 0
                )
                delay(3500)
                if (!TransferActivityGuard.isTransferActive()) {
                    capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
                }
            }
            showQueueEnabled && pendingCount > 0 -> {
                val first = pendingItems.firstOrNull()
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Pending,
                    title = first?.displayLabel ?: "Queue Staged",
                    subtitle = "$pendingCount files pending",
                    progress = 0f,
                    pendingCount = pendingCount
                )
            }
            else -> {
                capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
            }
        }
    }


    if (capsuleData.state == CapsuleState.Hidden) return

    val topMargin = if (cutoutPaddingTop > 0) cutoutPaddingTop.dp else 12.dp

    Box(
        modifier = modifier
            .padding(top = topMargin)
            .fillMaxWidth(),
        contentAlignment = if (isCornerCutout) Alignment.TopEnd else Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = capsuleData.state != CapsuleState.Hidden,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 180.dp, max = 340.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .clickable { expanded = !expanded },
                color = Color.Black.copy(alpha = 0.90f),
                contentColor = Color.White,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(FileApexTeal),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when (capsuleData.state) {
                                        CapsuleState.Completed -> "✓"
                                        CapsuleState.Error -> "!"
                                        CapsuleState.Pending -> "⏳"
                                        else -> "⇄"
                                    },
                                    color = Color.Black,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )


                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = capsuleData.title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (capsuleData.subtitle.isNotBlank()) {
                                    Text(
                                        text = capsuleData.subtitle,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp
                                        ),
                                        color = Color.White.copy(alpha = 0.70f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (capsuleData.state == CapsuleState.Transferring) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { capsuleData.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = FileApexTeal,
                            trackColor = Color.White.copy(alpha = 0.2f)
                        )
                    }
                }
            }
        }
    }
}
