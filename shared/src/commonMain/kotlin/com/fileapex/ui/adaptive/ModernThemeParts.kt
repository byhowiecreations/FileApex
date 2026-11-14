package com.fileapex.ui.adaptive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.i18n.stringRes
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.presentation.DevicesUiState
import com.fileapex.ui.ExplorerIcon
import com.fileapex.ui.HomeTab
import com.fileapex.ui.devicesNavLabel
import com.fileapex.ui.explorerIcon
import com.fileapex.ui.theme.CarouselPalette
import com.fileapex.ui.theme.CleanModernPalette
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.ui.theme.FileApexTealDark
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.fileApexChromeTopEdge
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.ui.theme.isFileApexJadedSteel
import fileapex.shared.generated.resources.Res
import fileapex.shared.generated.resources.ic_fileapex_brand
import org.jetbrains.compose.resources.painterResource

@Composable
internal fun WideTopBarBrandMark() {
    Image(
        painter = painterResource(Res.drawable.ic_fileapex_brand),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
    )
}

@Composable
internal fun ModernHeaderPowerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFlux = LocalAppTheme.current.traits.fluxSurfaces
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverBg = if (isFlux) FluxGlassPalette.headerHover else CleanModernPalette.HeaderActionHover
    val tint = if (hovered) Color(0xFFEF4444) else if (isFlux) FluxGlassPalette.headerIcon else CleanModernPalette.ChromeMuted
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .hoverable(interaction)
            .background(if (hovered) hoverBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = explorerIcon(ExplorerIcon.Power),
            contentDescription = stringRes("exit_fileapex"),
            tint = tint,
            modifier = Modifier.size(18.dp)
        )
    }
}

internal fun devicesListPaneContainerColor(theme: AppTheme, isFluent: Boolean): Color = when {
    theme == AppTheme.CLEAN && !isFluent -> CleanModernPalette.CanvasBackdrop
    theme.traits.glassChrome -> Color.Transparent
    else -> Color.Unspecified
}

@Composable
internal fun ModernDetailEmptyState(
    onOpenLocalFiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isFlux = LocalAppTheme.current.traits.fluxSurfaces
    val badgeBg = if (isFlux) FluxGlassPalette.emptyBadgeBg else CleanModernPalette.EmptyBadgeBg
    val badgeBorder = if (isFlux) FluxGlassPalette.cardBorder else FileApexTeal.copy(alpha = 0.14f)
    val iconTint = if (isFlux) FluxGlassPalette.emptyBadgeTint else FileApexTealDark
    val titleColor = if (isFlux) Color.White else CleanModernPalette.ChromeContent
    val bodyColor = if (isFlux) FluxGlassPalette.explorerMuted else CleanModernPalette.ChromeMuted
    val hintKey = if (usesDesktopFileSelection()) "select_device_files_dnd_hint" else "select_device_files_hint"

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(badgeBg)
                .border(1.dp, badgeBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.CreateNewFolder,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(18.dp))
        Text(
            text = stringRes("select_device_files"),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = titleColor,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringRes(hintKey),
            style = MaterialTheme.typography.bodyMedium,
            color = bodyColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedButton(
            onClick = onOpenLocalFiles,
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(
                1.dp,
                if (isFlux) FluxGlassPalette.cardBorder else CleanModernPalette.CardBorder
            ),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (isFlux) FluxGlassPalette.clipboardButtonFill else CleanModernPalette.CardSurface,
                contentColor = if (isFlux) Color.White else CleanModernPalette.ChromeContent
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringRes("open_local_files"),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium)
            )
        }
    }
}

@Composable
internal fun CleanModernBottomBar(
    selected: HomeTab,
    onMainHomeScreen: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit
) {
    val devicesLabel = devicesNavLabel(onMainHomeScreen)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .fileApexChromeTopEdge(),
        color = CleanModernPalette.ChromeSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CleanModernBottomTab(
                selected = selected == HomeTab.Devices,
                onClick = onDevices,
                icon = if (selected == HomeTab.Devices) Icons.Filled.Devices else Icons.Outlined.Devices,
                label = devicesLabel,
                modifier = Modifier.weight(1f)
            )
            CleanModernBottomTab(
                selected = selected == HomeTab.Files,
                onClick = onFiles,
                icon = if (selected == HomeTab.Files) Icons.Filled.Folder else Icons.Outlined.Folder,
                label = stringRes("local_files"),
                modifier = Modifier.weight(1f)
            )
            CleanModernBottomTab(
                selected = selected == HomeTab.Settings,
                onClick = onSettings,
                icon = if (selected == HomeTab.Settings) Icons.Filled.Settings else Icons.Outlined.Settings,
                label = stringRes("settings"),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun CleanModernBottomTab(
    selected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) CleanModernPalette.NavSelectedPill else Color.Transparent
    val tint = if (selected) FileApexTealDark else CleanModernPalette.ChromeMuted
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 11.sp
            ),
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun DockedDevicesActionBar(
    state: DevicesUiState,
    addMenuOpen: Boolean,
    onAddMenuOpenChange: (Boolean) -> Unit,
    onSendClipboard: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit
) {
    val isFlux = LocalAppTheme.current.traits.fluxSurfaces
    val cleanModern = isFileApexCleanModern()
    val footerBg = if (cleanModern) CleanModernPalette.ChromeSurface else Color.Transparent
    val dividerColor = if (isFlux) FluxGlassPalette.footerDivider else CleanModernPalette.ChromeBorder
    val clipBg = if (isFlux) FluxGlassPalette.clipboardButtonFill else CleanModernPalette.CardSurface
    val clipBorder = if (isFlux) FluxGlassPalette.cardBorder else CleanModernPalette.CardBorder
    val clipIconTint = if (isFlux) FluxGlassPalette.headerIcon else FileApexTealDark
    val clipText = if (isFlux) Color.White else CleanModernPalette.ChromeContent
    val addBg = if (isFlux) FluxGlassPalette.addButtonFill else FileApexTeal
    val addContent = if (isFlux) FluxGlassPalette.addButtonContent else Color.White
    val btnShape = RoundedCornerShape(10.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(footerBg)
    ) {
        HorizontalDivider(thickness = 1.dp, color = dividerColor)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .heightIn(min = 40.dp)
                .height(IntrinsicSize.Max),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onSendClipboard,
                enabled = !state.isSendingClipboard,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                shape = btnShape,
                color = clipBg,
                border = BorderStroke(1.dp, clipBorder),
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (state.isSendingClipboard) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = clipIconTint
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ContentPaste,
                            contentDescription = null,
                            tint = clipIconTint,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringRes("send_clipboard"),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        ),
                        color = if (state.isSendingClipboard) clipText.copy(alpha = 0.6f) else clipText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    onClick = { onAddMenuOpenChange(true) },
                    modifier = Modifier.fillMaxSize(),
                    shape = btnShape,
                    color = addBg,
                    shadowElevation = 0.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = addContent,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringRes("add_device"),
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            ),
                            color = addContent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                DropdownMenu(
                    expanded = addMenuOpen,
                    onDismissRequest = { onAddMenuOpenChange(false) }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringRes("generate_qr")) },
                        onClick = {
                            onAddMenuOpenChange(false)
                            onGenerateQr()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("join_device")) },
                        onClick = {
                            onAddMenuOpenChange(false)
                            onJoinDevice()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringRes("friends_entry")) },
                        onClick = {
                            onAddMenuOpenChange(false)
                            com.fileapex.friends.ui.FriendsUi.open()
                        }
                    )
                }
            }
        }
    }
}

@Composable
internal fun OnlineStatusDot(isFlux: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (isFlux) FluxGlassPalette.statusOnlineDot else CleanModernPalette.StatusOnlineDot)
    )
}

@Composable
internal fun LegacyHeaderPowerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val jaded = isFileApexJadedSteel()
    val accent = when {
        jaded -> KineticStyleLook.steel
        LocalAppTheme.current == AppTheme.CAROUSEL -> CarouselPalette.accent
        else -> Color(0xFF00E676)
    }
    val iconColor = if (jaded) KineticStyleLook.steel else accent
    Box(modifier = modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (jaded) {
            JadedRaisedTile(tileSize = 28.dp) {
                Icon(explorerIcon(ExplorerIcon.Power), stringRes("exit_fileapex"), Modifier.size(16.dp), tint = iconColor)
            }
        } else {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = CircleShape,
                color = accent.copy(alpha = 0.20f),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.70f))
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(explorerIcon(ExplorerIcon.Power), stringRes("exit_fileapex"), Modifier.size(16.dp), tint = iconColor)
                }
            }
        }
    }
}
