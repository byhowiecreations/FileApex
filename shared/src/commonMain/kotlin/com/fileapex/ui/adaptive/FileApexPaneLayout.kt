package com.fileapex.ui.adaptive

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.i18n.stringRes
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Shared list-pane header spacing for wide Devices / Settings / explorer panes. */
object FileApexPaneLayout {
    val headerHorizontalPadding = 20.dp
    val headerVerticalPadding = 12.dp
}

@Composable
fun FileApexPaneSectionHeader(

    title: String,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val isFluxGlass = LocalAppTheme.current == AppTheme.FLUX_GLASS
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = FileApexPaneLayout.headerHorizontalPadding,
                vertical = FileApexPaneLayout.headerVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringRes("back"),
                    tint = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (isFluxGlass) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        actions()
    }
}

