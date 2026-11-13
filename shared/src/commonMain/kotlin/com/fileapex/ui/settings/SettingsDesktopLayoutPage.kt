package com.fileapex.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.adaptive.simple.SimpleModernPalette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DesktopLayoutSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onExpanded: (Boolean) -> Unit
) {
    val currentPosition by FileApexServices.settings.desktopNavPosition.collectAsState()
    SettingsPageShell(
        title = stringRes("nav_position_title"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringRes("nav_position_desc"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            DesktopNavPosition.entries.forEach { position ->
                val selected = currentPosition == position
                val title = stringRes(
                    when (position) {
                        DesktopNavPosition.Left -> "nav_position_left"
                        DesktopNavPosition.Bottom -> "nav_position_bottom"
                        DesktopNavPosition.Right -> "nav_position_right"
                    }
                )
                val subtitle = stringRes(
                    when (position) {
                        DesktopNavPosition.Left -> "nav_position_left_desc"
                        DesktopNavPosition.Bottom -> "nav_position_bottom_desc"
                        DesktopNavPosition.Right -> "nav_position_right_desc"
                    }
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { FileApexServices.settings.setDesktopNavPosition(position) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        NavPositionPreviewThumbnail(position = position)
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        RadioButton(
                            selected = selected,
                            onClick = { FileApexServices.settings.setDesktopNavPosition(position) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavPositionPreviewThumbnail(position: DesktopNavPosition) {
    Surface(
        modifier = Modifier.size(width = 76.dp, height = 52.dp),
        shape = RoundedCornerShape(10.dp),
        color = SimpleModernPalette.BackgroundDark,
        border = BorderStroke(1.dp, SimpleModernPalette.ChromeBorder)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(SimpleModernPalette.ChromeSurface)
            )
            when (position) {
                DesktopNavPosition.Left -> Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(SimpleModernPalette.AccentMint)
                    )
                    PreviewBodyCards(modifier = Modifier.weight(1f).fillMaxHeight())
                }
                DesktopNavPosition.Right -> Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    PreviewBodyCards(modifier = Modifier.weight(1f).fillMaxHeight())
                    Box(
                        modifier = Modifier
                            .width(14.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(SimpleModernPalette.AccentMint)
                    )
                }
                DesktopNavPosition.Bottom -> Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    PreviewBodyCards(modifier = Modifier.weight(1f).fillMaxWidth())
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SimpleModernPalette.AccentMint)
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewBodyCards(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(SimpleModernPalette.CardSurface)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .weight(0.8f)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF1F3238))
        )
    }
}
