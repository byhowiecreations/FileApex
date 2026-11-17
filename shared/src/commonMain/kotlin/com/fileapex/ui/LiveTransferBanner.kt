package com.fileapex.ui

import com.fileapex.ui.transfer.TransferRowDensity
import com.fileapex.ui.transfer.LiveTransferRow
import com.fileapex.ui.motion.FileApexMotion
import com.fileapex.ui.motion.LocalReducedMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.FileApexTeal

/** True where the bottom transfer banner is shown, so a transfer-start snackbar would only repeat it. */
val LocalShowsTransferBanner = staticCompositionLocalOf { false }

/**
 * Status snackbars that announce a transfer are dropped when the transfer shows its own progress.
 * The short wait lets a transfer that is just starting register as active first.
 */
suspend fun shouldShowStatusSnackbar(progressShownElsewhere: Boolean): Boolean {
    if (!progressShownElsewhere) return true
    delay(STATUS_SNACKBAR_SETTLE_MS)
    return !TransferActivityGuard.isTransferActiveFlow.value
}

private const val STATUS_SNACKBAR_SETTLE_MS = 400L

/**
 * Modern Material 3 Expressive (M3E) floating pill transfer capsule shown across
 * Desktop (Mac/Windows) and Compact views during in-flight file transfers.
 */
@Composable
fun LiveTransferBanner(
    modifier: Modifier = Modifier,
    onOpenTransferQueue: () -> Unit = {}
) {
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val isTransferActive by TransferActivityGuard.isTransferActiveFlow.collectAsState()
    val pendingItems by FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())
    val activeItem = pendingItems.firstOrNull { it.isSending }
    val showBanner = isTransferActive || liveStats.isActive || activeItem != null

    AnimatedVisibility(
        visible = showBanner,
        enter = FileApexMotion.slideUpIn(LocalReducedMotion.current),
        exit = FileApexMotion.slideDownOut(LocalReducedMotion.current),
        modifier = modifier
    ) {
        val currentTheme = LocalAppTheme.current
        val isCustomGlass = currentTheme.traits.glassNotesSurfaces
        val accentColor = if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
        val surfaceColor = if (isCustomGlass) {
            Color(0xFF0F172A).copy(alpha = 0.92f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        }
        val borderColor = if (isCustomGlass) {
            accentColor.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable(onClick = onOpenTransferQueue),
                shape = RoundedCornerShape(18.dp),
                color = surfaceColor,
                border = BorderStroke(1.dp, borderColor),
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LiveTransferRow(
                        density = TransferRowDensity.Compact,
                        modifier = Modifier.weight(1f),
                        accent = accentColor,
                        textColor = if (isCustomGlass) Color.White else MaterialTheme.colorScheme.onSurface,
                        mutedColor = if (isCustomGlass) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringRes("queued_files"),
                        tint = if (isCustomGlass) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
