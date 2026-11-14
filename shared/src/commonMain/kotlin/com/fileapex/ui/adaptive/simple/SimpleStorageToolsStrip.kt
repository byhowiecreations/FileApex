package com.fileapex.ui.adaptive.simple

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object SimpleStorageToolsStripMetrics {
    const val minVerticalPaddingDp = 14
    const val maxSubtitleLines = 2
}

/** Lightweight one-shot signal so Simple Home can jump directly into Local Files -> Storage Tools. */
internal object LocalExplorerOpenToolsSignal {
    private val _requests = MutableStateFlow(0)
    val requests: StateFlow<Int> = _requests.asStateFlow()

    fun requestOpen() {
        _requests.value = _requests.value + 1
    }

    fun requestOpenIfPermitted(hasStoragePermission: Boolean) {
        if (hasStoragePermission) requestOpen()
    }

    fun consume(version: Int) {
        if (_requests.value == version) {
            _requests.value = 0
        }
    }
}

@Composable
internal fun SimpleStorageToolsStrip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = SimpleModernPalette.CardSurface,
        border = BorderStroke(1.dp, SimpleModernPalette.CardBorder)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = SimpleStorageToolsStripMetrics.minVerticalPaddingDp.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SimpleModernPalette.PillTint),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.CleaningServices,
                    contentDescription = null,
                    tint = SimpleModernPalette.AccentMintSoft,
                    modifier = Modifier.size(19.dp)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringRes("storage_tools_title"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SimpleModernPalette.TextPrimary
                )
                Text(
                    text = stringRes("storage_tools_strip_desc"),
                    style = MaterialTheme.typography.bodySmall,
                    color = SimpleModernPalette.TextSecondary,
                    maxLines = SimpleStorageToolsStripMetrics.maxSubtitleLines,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = SimpleModernPalette.TextSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
