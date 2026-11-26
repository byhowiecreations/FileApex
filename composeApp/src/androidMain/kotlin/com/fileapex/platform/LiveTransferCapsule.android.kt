package com.fileapex.platform

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import com.fileapex.domain.transfer.liveTransferCapsuleContent
import com.fileapex.i18n.AppI18n
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
    val percentLabel: String = "",
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
    val isReceiving: Boolean = false,
    val pendingCount: Int = 0
)

@Composable
fun LiveTransferCapsuleOverlay(
    modifier: Modifier = Modifier,
    isCornerCutout: Boolean = false
) {
    val settings = FileApexServices.settings
    val capsuleEnabled by settings.liveTransferCapsuleEnabled.collectAsState()
    val transferNotificationsEnabled by settings.fileTransferNotificationsEnabled.collectAsState()
    val isTransferActive by TransferActivityGuard.isTransferActiveFlow.collectAsState()
    val liveProgress by TransferActivityGuard.transferProgressFlow.collectAsState()
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())

    var capsuleData by remember { mutableStateOf(CapsuleDisplayData()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(
        capsuleEnabled,
        transferNotificationsEnabled,
        isTransferActive,
        liveProgress,
        liveStats,
        pendingItems
    ) {
        if (!capsuleEnabled || !transferNotificationsEnabled) {
            capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
            return@LaunchedEffect
        }

        val activeSendingItem = pendingItems.firstOrNull { it.isSending }
        val pendingCount = pendingItems.size

        when {
            isTransferActive || activeSendingItem != null -> {
                val content = liveTransferCapsuleContent(
                    stats = liveStats,
                    queueCount = pendingCount,
                    activeSendingSummary = activeSendingItem?.sourceSummary
                )
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Transferring,
                    title = content.title,
                    subtitle = content.subtitle,
                    percentLabel = content.percentLabel,
                    progress = content.progress,
                    indeterminate = content.indeterminate,
                    isReceiving = content.isReceiving,
                    pendingCount = pendingCount
                )
            }

            capsuleData.state == CapsuleState.Transferring && !isTransferActive -> {
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Completed,
                    title = AppI18n.t("transfer_complete"),
                    subtitle = AppI18n.t("all_files_transmitted"),
                    progress = 1f,
                    pendingCount = 0
                )
                delay(3500)
                if (!TransferActivityGuard.isTransferActive()) {
                    capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
                }
            }
            else -> {
                capsuleData = CapsuleDisplayData(CapsuleState.Hidden)
            }
        }
    }

    if (capsuleData.state == CapsuleState.Hidden) return

    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topMargin = (statusBarPadding + 8.dp).coerceAtLeast(44.dp)
    val capsuleShape = RoundedCornerShape(20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = topMargin, start = 16.dp, end = 16.dp),
        contentAlignment = if (isCornerCutout) Alignment.TopEnd else Alignment.TopCenter
    ) {
        AnimatedVisibility(
            visible = capsuleData.state != CapsuleState.Hidden,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .clip(capsuleShape)
                    .clickable { expanded = !expanded },
                shape = capsuleShape,
                color = Color(0xFF181818).copy(alpha = 0.96f),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(FileApexTeal),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = when (capsuleData.state) {
                                    CapsuleState.Completed -> "✓"
                                    CapsuleState.Error -> "!"
                                    CapsuleState.Pending -> "⏳"
                                    else -> if (capsuleData.isReceiving) "↓" else "↑"
                                },
                                color = Color.Black,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = capsuleData.title,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                if (capsuleData.percentLabel.isNotBlank()) {
                                    Text(
                                        text = capsuleData.percentLabel,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 12.sp
                                        ),
                                        color = FileApexTeal,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (capsuleData.subtitle.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = capsuleData.subtitle,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontSize = 12.sp
                                    ),
                                    color = Color.White.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (capsuleData.state == CapsuleState.Transferring) {
                        Spacer(modifier = Modifier.height(10.dp))
                        val barModifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                        if (capsuleData.indeterminate) {
                            LinearProgressIndicator(
                                modifier = barModifier,
                                color = FileApexTeal,
                                trackColor = Color.White.copy(alpha = 0.2f)
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { capsuleData.progress },
                                modifier = barModifier,
                                color = FileApexTeal,
                                trackColor = Color.White.copy(alpha = 0.2f),
                                gapSize = 0.dp,
                                drawStopIndicator = {}
                            )
                        }
                    }
                }
            }
        }
    }
}
