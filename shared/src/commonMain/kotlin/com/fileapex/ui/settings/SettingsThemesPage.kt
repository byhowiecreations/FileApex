package com.fileapex.ui.settings

import com.fileapex.ui.SettingsScreenLayoutMode
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.fileapex.ui.theme.FluxGlassPalette
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.supportedIconStyles
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.carousel.rememberCarouselAvailable
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.isFileApexCustomGlassTheme

@Composable
internal fun localizedThemeName(theme: AppTheme): String = when (theme) {
    AppTheme.SIMPLE -> stringRes("theme_simple")
    AppTheme.CLEAN -> stringRes("theme_clean")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic")
    AppTheme.FREESTYLE -> stringRes("theme_freestyle")
    AppTheme.CAROUSEL -> stringRes("theme_carousel")
}

@Composable
internal fun localizedThemeDescription(theme: AppTheme): String = when (theme) {
    AppTheme.SIMPLE -> stringRes("theme_simple_desc")
    AppTheme.CLEAN -> stringRes("theme_clean_desc")
    AppTheme.FLUX_GLASS -> stringRes("theme_flux_desc")
    AppTheme.KINETIC_SPHERE -> stringRes("theme_kinetic_desc")
    AppTheme.FREESTYLE -> stringRes("theme_freestyle_desc")
    AppTheme.CAROUSEL -> stringRes("theme_carousel_desc")
}

@Composable
internal fun localizedKineticStyleName(style: KineticStyle): String = when (style) {
    KineticStyle.SPACE -> stringRes("kinetic_style_space")
    KineticStyle.JADED_STEEL -> stringRes("kinetic_style_jaded_steel")
}

@Composable
private fun KineticSegmentRow(
    label: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit
) {
    val isCustomTheme = isFileApexCustomGlassTheme()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = if (isCustomTheme) FluxGlassPalette.explorerMuted else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = if (isCustomTheme) FluxGlassPalette.unselectedFill else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(
                1.dp,
                if (isCustomTheme) Color.White.copy(alpha = 0.18f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            )
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(3.dp)) {
                options.forEachIndexed { index, (title, selected) ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (selected) {
                                    if (isCustomTheme) FluxGlassPalette.accent else MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                }
                            )
                            .clickable { onSelect(index) }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (selected) {
                                if (isCustomTheme) FluxGlassPalette.onPrimary else Color.White
                            } else if (isCustomTheme) {
                                Color.White
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun localizedIconStyleName(style: ThemeIconStyle): String = when (style) {
    ThemeIconStyle.STANDARD -> stringRes("icon_style_standard")
    ThemeIconStyle.FLUX -> stringRes("icon_style_flux")
    ThemeIconStyle.FREESTYLE -> stringRes("icon_style_freestyle")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThemesSettingsPage(
    state: SettingsUiState,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onSelectTheme: (AppTheme) -> Unit,
    onSelectThemeIconStyle: (ThemeIconStyle) -> Unit,
    onSelectKineticStyle: (KineticStyle) -> Unit,
    onToggleConnectedLines: (Boolean) -> Unit,
    onToggleOrbitalRings: (Boolean) -> Unit,
    onTogglePersistentWallpaper: (Boolean) -> Unit = {}
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
            val carouselAvailable = rememberCarouselAvailable()
            AppTheme.entries.filter { it != AppTheme.CAROUSEL || carouselAvailable || state.appTheme == AppTheme.CAROUSEL }.forEach { theme ->
                val selected = state.appTheme == theme
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectTheme(theme) },
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
                                if (theme == AppTheme.DEFAULT) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = if (isCustomTheme) FluxGlassPalette.badgeScrim else MaterialTheme.colorScheme.secondaryContainer,
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
                                color = if (isCustomTheme) FluxGlassPalette.explorerMuted else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        RadioButton(
                            selected = selected,
                            onClick = { onSelectTheme(theme) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = if (isCustomTheme) FluxGlassPalette.accent else MaterialTheme.colorScheme.primary,
                                unselectedColor = if (isCustomTheme) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }

                val supportedIcons = theme.supportedIconStyles()
                if (selected && theme == AppTheme.KINETIC_SPHERE) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        KineticSegmentRow(
                            label = stringRes("icon_pack"),
                            options = supportedIcons.map { localizedIconStyleName(it) to (state.themeIconStyle == it) },
                            onSelect = { index -> onSelectThemeIconStyle(supportedIcons[index]) }
                        )
                        KineticSegmentRow(
                            label = stringRes("kinetic_style"),
                            options = KineticStyle.entries.map { localizedKineticStyleName(it) to (state.kineticStyle == it) },
                            onSelect = { index -> onSelectKineticStyle(KineticStyle.entries[index]) }
                        )
                    }
                } else if (selected && supportedIcons.size > 1) {
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
                                    if (isIconSelected) FluxGlassPalette.accentSoft else FluxGlassPalette.unselectedFill
                                } else {
                                    if (isIconSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                },
                                border = BorderStroke(
                                    width = if (isIconSelected) 1.5.dp else 1.dp,
                                    color = if (isCustomTheme) {
                                        if (isIconSelected) FluxGlassPalette.accent else Color.White.copy(alpha = 0.18f)
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
                                            if (isIconSelected) FluxGlassPalette.accent else Color(0xFFFFB74D)
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

                if (state.kineticSphereOrbitalRingsEnabled) {
                    ListItem(
                        headlineContent = { Text(stringRes("kinetic_sphere_persistent_wallpaper"), softWrap = true) },
                        supportingContent = {
                            Text(stringRes("kinetic_sphere_persistent_wallpaper_desc"), softWrap = true)
                        },
                        trailingContent = {
                            Switch(
                                checked = state.kineticSpherePersistentWallpaperEnabled,
                                onCheckedChange = onTogglePersistentWallpaper
                            )
                        }
                    )
                }
            }
        }
    }
}
