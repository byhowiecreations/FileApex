package com.fileapex.ui.explorer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.domain.model.RemoteFileItem
import com.fileapex.i18n.formatLocalizedDate
import com.fileapex.i18n.formatLocalizedTime
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.ui.explorerInk
import com.fileapex.ui.formatBytes
import com.fileapex.util.systemZoneId

/** Left pane width that fits the longest folder name, clamped so one long name cannot take over the window. */
object ExplorerPaneFit {
    const val MIN_FRACTION = 0.2f
    const val MAX_FRACTION = 0.35f
    const val DEFAULT_FRACTION = 0.3f

    fun fraction(longestNameDp: Float, chromeDp: Float, totalWidthDp: Float): Float {
        if (totalWidthDp <= 0f) return DEFAULT_FRACTION
        return ((longestNameDp + chromeDp) / totalWidthDp).coerceIn(MIN_FRACTION, MAX_FRACTION)
    }
}

enum class ExplorerDetailsColumn(val sortMode: ExplorerSortMode, val labelKey: String) {
    Name(ExplorerSortMode.Name, "sort_name"),
    Size(ExplorerSortMode.Size, "sort_size"),
    Modified(ExplorerSortMode.Date, "sort_date")
}

/** Column setup for the wide-display details table; [showModified] drops the date column in narrow panes. */
data class ExplorerDetails(
    val sortMode: ExplorerSortMode,
    val onSort: (ExplorerSortMode) -> Unit,
    val showModified: Boolean
)

internal val DetailsSizeWidth: Dp = 80.dp
internal val DetailsModifiedWidth: Dp = 170.dp
internal val DetailsColumnGap: Dp = 32.dp

@Composable
internal fun ExplorerDetailsHeader(details: ExplorerDetails, leadingInset: Dp) {
    val ink = explorerInk()
    Column(Modifier.fillMaxWidth().background(ink.paneBackground)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = leadingInset, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExplorerDetailsColumn.entries.forEach { column ->
            if (column == ExplorerDetailsColumn.Modified && !details.showModified) return@forEach
            val active = details.sortMode == column.sortMode
            val label = stringRes(column.labelKey) + if (active && column != ExplorerDetailsColumn.Name) " ↓" else ""
            val cell = when (column) {
                ExplorerDetailsColumn.Name -> Modifier.weight(1f)
                ExplorerDetailsColumn.Size -> Modifier.width(DetailsSizeWidth)
                ExplorerDetailsColumn.Modified -> Modifier.width(DetailsModifiedWidth)
            }
            Text(
                text = label,
                modifier = cell.clickable { details.onSort(column.sortMode) },
                style = MaterialTheme.typography.labelMedium,
                color = if (active) ink.accent else ink.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (column == ExplorerDetailsColumn.Size) TextAlign.End else TextAlign.Start
            )
            if (column == ExplorerDetailsColumn.Size) Spacer(Modifier.width(DetailsColumnGap))
        }
    }
    HorizontalDivider(color = ink.divider)
    }
}

@Composable
internal fun ExplorerDetailsCells(item: RemoteFileItem, details: ExplorerDetails) {
    val ink = explorerInk()
    Text(
        text = if (item.isDirectory) "" else formatBytes(item.sizeBytes),
        modifier = Modifier.width(DetailsSizeWidth),
        style = MaterialTheme.typography.bodySmall,
        color = ink.muted,
        maxLines = 1,
        textAlign = TextAlign.End
    )
    if (details.showModified) {
        Spacer(Modifier.width(DetailsColumnGap))
        Text(
            text = modifiedLabel(item.lastModified),
            modifier = Modifier.width(DetailsModifiedWidth),
            style = MaterialTheme.typography.bodySmall,
            color = ink.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun modifiedLabel(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    val zone = systemZoneId()
    return formatLocalizedDate(epochMs, zone) + " " + formatLocalizedTime(epochMs, zone)
}
