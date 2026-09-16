package com.fileapex.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.supportedIconStyles
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.isFileApexCustomGlassTheme

@Composable
internal fun localizedThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.CLEAN -> stringRes("theme_clean")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic")
    AppTheme.FREESTYLE -> stringRes("theme_freestyle")
}

@Composable
internal fun localizedThemeDescription(theme: AppTheme): String = when (theme) {
    AppTheme.CLEAN -> stringRes("theme_clean_desc")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux_desc")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic_desc")
    AppTheme.FREESTYLE -> stringRes("theme_freestyle_desc")
}

@Composable
internal fun localizedIconStyleName(style: ThemeIconStyle): String = when (style) {
    ThemeIconStyle.STANDARD -> stringRes("icon_style_standard")
    ThemeIconStyle.FLUX -> stringRes("icon_style_flux")
    ThemeIconStyle.FREESTYLE -> stringRes("icon_style_freestyle")
}

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
internal fun localizedBulletinBoardStyleDescription(style: BulletinBoardStyle): String = when (style) {
    BulletinBoardStyle.DEFAULT -> stringRes("bulletin_style_default_desc")
    BulletinBoardStyle.IOS_MODERN -> stringRes("bulletin_style_ios_modern_desc")
    BulletinBoardStyle.MATERIAL_YOU -> stringRes("bulletin_style_material_you_desc")
    BulletinBoardStyle.AERO_GLASS -> stringRes("bulletin_style_aero_glass_desc")
    BulletinBoardStyle.TORN_LEDGER -> stringRes("bulletin_style_torn_ledger_desc")
    BulletinBoardStyle.STICKY_NOTE -> stringRes("bulletin_style_sticky_note_desc")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemesSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectThemeIconStyle: (ThemeIconStyle) -> Unit,
    onToggleConnectedLines: (Boolean) -> Unit,
    onToggleOrbitalRings: (Boolean) -> Unit
) {
    SettingsPageShell(
        title = stringRes("themes"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            FileApexPaneSectionHeader(title = stringRes("app_theme"))

            Text(
                text = stringRes("themes_intro"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            val isCustomTheme = isFileApexCustomGlassTheme()
            AppTheme.entries.forEach { theme ->
                val selected = state.appTheme == theme
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectTheme(theme) },
                    color = if (isCustomTheme) {
                        if (selected) Color(0x3300E676) else Color(0x221E2D34)
                    } else {
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (isCustomTheme) {
                            if (selected) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)
                        } else {
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        }
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = localizedThemeName(theme),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSurface
                                )
                                if (theme == AppTheme.CLEAN) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isCustomTheme) Color(0x44FFFFFF) else MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = stringRes("default_badge"),
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = if (isCustomTheme) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = localizedThemeDescription(theme),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isCustomTheme) Color(0xFFCBD5E1) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        RadioButton(
                            selected = selected,
                            onClick = { onSelectTheme(theme) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isCustomTheme) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                val supportedIcons = theme.supportedIconStyles()
                if (selected && supportedIcons.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        supportedIcons.forEach { iconStyle ->
                            val isIconSelected = state.themeIconStyle == iconStyle
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectThemeIconStyle(iconStyle) },
                                color = if (isCustomTheme) {
                                    if (isIconSelected) Color(0x3300E676) else Color(0x221E2D34)
                                } else {
                                    if (isIconSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                border = BorderStroke(
                                    width = if (isIconSelected) 1.5.dp else 1.dp,
                                    color = if (isCustomTheme) {
                                        if (isIconSelected) Color(0xFF00E676) else Color.White.copy(alpha = 0.18f)
                                    } else {
                                        if (isIconSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    }
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = localizedIconStyleName(iconStyle),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                        color = if (isCustomTheme) {
                                            if (isIconSelected) Color(0xFF00E676) else Color(0xFFFFB74D)
                                        } else {
                                            if (isIconSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    RadioButton(
                                        selected = isIconSelected,
                                        onClick = { onSelectThemeIconStyle(iconStyle) },
                                        modifier = Modifier.size(20.dp),
                                        colors = RadioButtonDefaults.colors(
                                            selectedColor = if (isCustomTheme) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                            unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (state.appTheme == AppTheme.KINETIC_SPHERE) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                FileApexPaneSectionHeader(title = stringRes("kinetic_sphere_elements"))

                ListItem(
                    headlineContent = { Text(stringRes("connected_device_lines"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("theme_lines_desc"), softWrap = true)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.kineticSphereConnectedLinesEnabled,
                            onCheckedChange = onToggleConnectedLines
                        )
                    }
                )

                ListItem(
                    headlineContent = { Text(stringRes("orbital_background_rings"), softWrap = true) },
                    supportingContent = {
                        Text(stringRes("theme_rings_desc"), softWrap = true)
                    },
                    trailingContent = {
                        Switch(
                            checked = state.kineticSphereOrbitalRingsEnabled,
                            onCheckedChange = onToggleOrbitalRings
                        )
                    }
                )
            }
        }
    }
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
                        if (selected) Color(0x3300E676) else Color(0x221E2D34)
                    } else {
                        if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    },
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (isCustomTheme) {
                            if (selected) Color(0xFF00E676) else Color.White.copy(alpha = 0.2f)
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
                                        color = if (isCustomTheme) Color(0x44FFFFFF) else MaterialTheme.colorScheme.secondaryContainer,
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
                                selectedColor = if (isCustomTheme) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                                unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    }
}
