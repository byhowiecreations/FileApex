package com.fileapex.ui.settings

import com.fileapex.ui.BulletinStylePreviewCard
import com.fileapex.ui.SettingsScreenLayoutMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.fileapex.ui.theme.FluxGlassPalette
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.isFileApexCustomGlassTheme
@Composable
internal fun localizedBulletinBoardStyleName(style: BulletinBoardStyle): String = when (style) {
    BulletinBoardStyle.DEFAULT -> stringRes("bulletin_style_default")
    BulletinBoardStyle.IOS_MODERN -> stringRes("bulletin_style_ios_modern")
    BulletinBoardStyle.MATERIAL_YOU -> stringRes("bulletin_style_material_you")
    BulletinBoardStyle.AERO_GLASS -> stringRes("bulletin_style_aero_glass")
    BulletinBoardStyle.TORN_LEDGER -> stringRes("bulletin_style_torn_ledger")
    BulletinBoardStyle.STICKY_NOTE -> stringRes("bulletin_style_sticky_note")
}

@Composable
internal fun BulletinBoardStylesSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onSelectStyle: (BulletinBoardStyle) -> Unit
) {
    SettingsPageShell(
        title = stringRes("bulletin_board_styles"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            FileApexPaneSectionHeader(title = stringRes("bulletin_board_styles"))

            Text(
                text = stringRes("bulletin_board_styles_intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val isCustomTheme = isFileApexCustomGlassTheme()
            val orderedStyles = listOf(
                BulletinBoardStyle.DEFAULT,
                BulletinBoardStyle.IOS_MODERN,
                BulletinBoardStyle.MATERIAL_YOU,
                BulletinBoardStyle.AERO_GLASS,
                BulletinBoardStyle.TORN_LEDGER,
                BulletinBoardStyle.STICKY_NOTE
            )

            orderedStyles.forEach { style ->
                val selected = state.bulletinBoardStyle == style
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectStyle(style) },
                    color = if (isCustomTheme) {
                        if (selected) FluxGlassPalette.accentSoft else FluxGlassPalette.unselectedFill
                    } else {
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (isCustomTheme) {
                            if (selected) FluxGlassPalette.accent else Color.White.copy(alpha = 0.2f)
                        } else {
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = localizedBulletinBoardStyleName(style),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    ),
                                    color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                if (style == BulletinBoardStyle.DEFAULT) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Surface(
                                        color = if (isCustomTheme) FluxGlassPalette.badgeScrim else MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = stringRes("default_badge"),
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            BulletinStylePreviewCard(style = style, compact = true)
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        RadioButton(
                            selected = selected,
                            onClick = { onSelectStyle(style) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isCustomTheme) FluxGlassPalette.accent else MaterialTheme.colorScheme.primary,
                                unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}
