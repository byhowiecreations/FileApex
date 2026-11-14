package com.fileapex.ui.adaptive

import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.ui.ExpandableSearchPillRow
import com.fileapex.ui.theme.isFileApexJadedSteel

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
    searchQuery: String = "",
    onSearchQueryChange: ((String) -> Unit)? = null,
    searchPlaceholder: String = "",
    searchButtonModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val currentTheme = LocalAppTheme.current
    val isGlassTheme = currentTheme.traits.glassChrome
    val jadedStyle = isFileApexJadedSteel()
    val titleColor = if (isGlassTheme) Color.White else MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = FileApexPaneLayout.headerHorizontalPadding,
                vertical = FileApexPaneLayout.headerVerticalPadding
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onSearchQueryChange != null) {
            ExpandableSearchPillRow(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = searchPlaceholder,
                collapseLeadingCompletely = true,
                useJadedStyle = jadedStyle,
                useLightChrome = isGlassTheme && !jadedStyle,
                modifier = Modifier.weight(1f),
                searchPillModifier = searchButtonModifier,
                leading = { leadingModifier, _ ->
                    Row(
                        modifier = leadingModifier,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringRes("back"),
                                    tint = titleColor
                                )
                            }
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = titleColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            )
            actions()
        } else {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringRes("back"),
                        tint = titleColor
                    )
                }
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                modifier = Modifier.weight(1f)
            )
            actions()
        }
    }
}

