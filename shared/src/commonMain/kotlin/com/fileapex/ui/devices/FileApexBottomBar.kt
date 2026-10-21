package com.fileapex.ui.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.ui.HomeTab
import com.fileapex.ui.adaptive.JadedRaisedTile
import com.fileapex.ui.devicesNavLabel
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.fileApexChromeContainerColor
import com.fileapex.ui.theme.fileApexChromeTopEdge
import com.fileapex.ui.theme.fileApexNavSelectedBackgroundColor
import com.fileapex.ui.theme.fileApexNavSelectedIconColor
import com.fileapex.ui.theme.fileApexNavSelectedTextColor
import com.fileapex.ui.theme.fileApexNavUnselectedIconColor
import com.fileapex.ui.theme.fileApexNavUnselectedTextColor
import com.fileapex.ui.theme.fileApexNavigationBarItemColors
import com.fileapex.ui.theme.isFileApexCleanCurved
import com.fileapex.ui.theme.isFileApexCustomGlassTheme
import com.fileapex.ui.theme.isFileApexJadedSteel

@Composable
fun FileApexBottomBar(
    selected: HomeTab,
    onMainHomeScreen: Boolean = true,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    val devicesLabel = devicesNavLabel(onMainHomeScreen)
    val currentTheme = LocalAppTheme.current
    if (currentTheme.traits.canvasHome) return

    val isCustomGlass = currentTheme.traits.glassNotesSurfaces
    val jadedNav = isFileApexJadedSteel()

    if (isCustomGlass) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = if (jadedNav) 8.dp else 12.dp, top = if (jadedNav) 2.dp else 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val pillWidth = if (maxWidth < 480.dp) {
                (maxWidth * 0.76f).coerceIn(285.dp, 360.dp)
            } else {
                (maxWidth * 0.333f).coerceIn(320.dp, 440.dp)
            }

            val pillShape = RoundedCornerShape(if (jadedNav) 20.dp else 32.dp)
            Surface(
                modifier = Modifier
                    .width(pillWidth)
                    .height(if (jadedNav) 58.dp else 64.dp),
                shape = pillShape,
                color = when {
                    jadedNav -> Color(0xCC121E28)
                    else -> FluxGlassPalette.actionPill
                },
                border = BorderStroke(1.dp, Color.White.copy(alpha = if (jadedNav) 0.10f else 0.30f)),
                shadowElevation = if (jadedNav) 0.dp else 10.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Devices,
                        onClick = onDevices,
                        icon = Icons.Filled.Devices,
                        label = devicesLabel,
                        jaded = jadedNav,
                        modifier = Modifier.weight(1f)
                    )
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Files,
                        onClick = onFiles,
                        icon = Icons.Filled.Folder,
                        label = stringRes("local_files"),
                        jaded = jadedNav,
                        modifier = Modifier.weight(1f)
                    )
                    CustomGlassNavItem(
                        selected = selected == HomeTab.Settings,
                        onClick = onSettings,
                        icon = Icons.Filled.Settings,
                        label = stringRes("settings"),
                        jaded = jadedNav,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    } else {
        NavigationBar(
            modifier = Modifier
                .navigationBarsPadding()
                .then(
                    if (isFileApexCleanCurved()) {
                        Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp).height(64.dp).clip(RoundedCornerShape(28.dp))
                    } else {
                        Modifier.fileApexChromeTopEdge()
                    }
                ),
            containerColor = fileApexChromeContainerColor(),
            contentColor = fileApexNavSelectedTextColor(),
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selected == HomeTab.Devices,
                onClick = onDevices,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Devices,
                        imageVector = Icons.Filled.Devices,
                        contentDescription = devicesLabel
                    )
                },
                label = {
                    Text(
                        devicesLabel,
                        color = if (selected == HomeTab.Devices) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
            NavigationBarItem(
                selected = selected == HomeTab.Files,
                onClick = onFiles,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Files,
                        imageVector = Icons.Filled.Folder,
                        contentDescription = stringRes("local_files")
                    )
                },
                label = {
                    Text(
                        stringRes("local_files"),
                        color = if (selected == HomeTab.Files) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
            NavigationBarItem(
                selected = selected == HomeTab.Settings,
                onClick = onSettings,
                icon = {
                    NavIcon(
                        selected = selected == HomeTab.Settings,
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringRes("settings")
                    )
                },
                label = {
                    Text(
                        stringRes("settings"),
                        maxLines = 2,
                        softWrap = true,
                        color = if (selected == HomeTab.Settings) {
                            fileApexNavSelectedTextColor()
                        } else {
                            fileApexNavUnselectedTextColor()
                        }
                    )
                },
                colors = fileApexNavigationBarItemColors()
            )
        }
    }
}

@Composable
private fun CustomGlassNavItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    jaded: Boolean = false,
    modifier: Modifier = Modifier
) {
    val activeBg = Color(0xFF00E676).copy(alpha = 0.22f)
    val activeTint = Color(0xFF00E676)
    val inactiveTint = Color.White.copy(alpha = 0.72f)

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (jaded) 0.dp else 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (jaded && selected) {
            JadedRaisedTile(tileSize = 22.dp) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color(0xFF8DB8F5),
                    modifier = Modifier.size(13.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .then(
                        if (jaded) {
                            Modifier.size(22.dp)
                        } else {
                            Modifier
                                .size(width = 52.dp, height = 30.dp)
                                .clip(RoundedCornerShape(15.dp))
                                .background(if (selected) activeBg else Color.Transparent)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = when {
                        jaded -> Color(0xFFC5D0D2)
                        selected -> activeTint
                        else -> inactiveTint
                    },
                    modifier = Modifier.size(if (jaded) 16.dp else 22.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(if (jaded) 2.dp else 3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                lineHeightStyle = if (jaded) {
                    LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                } else {
                    null
                }
            ),
            color = when {
                jaded && selected -> Color.White
                jaded -> Color(0xFFC5D0D2)
                selected -> Color.White
                else -> inactiveTint
            },
            maxLines = if (jaded) 1 else 2,
            softWrap = !jaded,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun NavIcon(
    selected: Boolean,
    imageVector: ImageVector,
    contentDescription: String
) {
    val isGlass = isFileApexCustomGlassTheme()
    val pillShape = if (isGlass) RoundedCornerShape(14.dp) else if (isFileApexCleanCurved()) RoundedCornerShape(15.dp) else RoundedCornerShape(12.dp)
    val pillSizeModifier = if (isGlass) Modifier.size(width = 48.dp, height = 26.dp) else if (isFileApexCleanCurved()) Modifier.size(width = 52.dp, height = 30.dp) else Modifier.size(40.dp)

    Box(
        modifier = pillSizeModifier
            .clip(pillShape)
            .background(if (selected) fileApexNavSelectedBackgroundColor() else Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = if (selected) {
                fileApexNavSelectedIconColor()
            } else {
                fileApexNavUnselectedIconColor()
            },
            modifier = Modifier.size(24.dp)
        )
    }
}
