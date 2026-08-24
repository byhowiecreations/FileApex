package com.fileapex.platform

import android.content.Context
import android.os.Build
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
    val transferNotificationsEnabled by settings.fileTransferNotificationsEnabled.collectAsState()
    val isTransferActive by TransferActivityGuard.isTransferActiveFlow.collectAsState()
    val liveProgress by TransferActivityGuard.transferProgressFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())

    var capsuleData by remember { mutableStateOf(CapsuleDisplayData()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(
        capsuleEnabled,
        transferNotificationsEnabled,
        isTransferActive,
        liveProgress,
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
                val isSingle = (activeSendingItem?.sourceSummary?.contains(",") != true) && pendingCount <= 1
                val title = if (isSingle) {
                    com.fileapex.i18n.AppI18n.t("sending_file")
                } else {
                    com.fileapex.i18n.AppI18n.t("sending_files")
                }
                val subtitle = if (pendingCount > 1) {
                    com.fileapex.i18n.AppI18n.plural("items_in_queue", pendingCount, pendingCount.toString())
                } else {
                    com.fileapex.i18n.AppI18n.t("active_transfer")
                }
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Transferring,
                    title = title,
                    subtitle = subtitle,
                    progress = liveProgress,
                    pendingCount = pendingCount
                )
            }

            capsuleData.state == CapsuleState.Transferring && !isTransferActive -> {
                capsuleData = CapsuleDisplayData(
                    state = CapsuleState.Completed,
                    title = com.fileapex.i18n.AppI18n.t("transfer_complete"),
                    subtitle = com.fileapex.i18n.AppI18n.t("all_files_transmitted"),
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
                    .widthIn(min = 200.dp, max = 340.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .clickable { expanded = !expanded },
                color = Color(0xFF181818).copy(alpha = 0.96f),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
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
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f, fill = false)) {
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
                                        color = Color.White.copy(alpha = 0.72f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    if (capsuleData.state == CapsuleState.Transferring) {
                        Spacer(modifier = Modifier.height(8.dp))
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

