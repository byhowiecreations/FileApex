package com.fileapex.ui.devices

import com.fileapex.ui.theme.fileApexAccentContainer
import com.fileapex.ui.motion.pressScale
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.data.settings.KineticStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.LocalKineticStyle
import com.fileapex.data.settings.LocalThemeIconStyle
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.domain.transfer.TransferActivityGuard
import com.fileapex.i18n.stringRes
import com.fileapex.platform.isDesktopHost
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.DeviceEntryIcon
import com.fileapex.ui.deviceIconFillsOrb
import com.fileapex.ui.dnd.deviceFileDropTarget
import com.fileapex.ui.theme.CleanModernPalette
import com.fileapex.ui.theme.fileApexAccent
import com.fileapex.ui.theme.fileApexAccentDark
import com.fileapex.ui.theme.FluxGlassPalette
import com.fileapex.ui.theme.KineticStyleLook
import com.fileapex.ui.theme.LocalFileApexUiStyle
import com.fileapex.ui.theme.isFileApexCleanModern
import com.fileapex.ui.theme.isFileApexCustomGlassTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
internal fun SendClipboardActionChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false
) {
    val glass = isFileApexCustomGlassTheme()
    val kineticStyle = if (LocalAppTheme.current.traits.orbitalHome) LocalKineticStyle.current else null
    val jaded = kineticStyle == KineticStyle.JADED_STEEL
    val chipShape = RoundedCornerShape(16.dp)
    Surface(
        onClick = onClick,
        enabled = enabled && !isLoading,
        modifier = modifier,
        shape = chipShape,
        color = when {
            jaded -> KineticStyleLook.jadedCard
            glass -> FluxGlassPalette.clipboardChip
            else -> MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            1.dp,
            when {
                isLoading -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                jaded -> KineticStyleLook.jadedCardEdge
                glass -> FluxGlassPalette.cyan.copy(alpha = 0.50f)
                else -> fileApexAccent().copy(alpha = 0.55f)
            }
        ),
        shadowElevation = when {
            isLoading -> 1.dp
            jaded -> 0.dp
            glass -> 8.dp
            else -> 2.dp
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(15.dp),
                    strokeWidth = 2.dp,
                    color = if (glass) FluxGlassPalette.accent else fileApexAccent()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ContentPaste,
                    contentDescription = null,
                    tint = when {
                        glass -> FluxGlassPalette.accent
                        else -> fileApexAccent()
                    },
                    modifier = Modifier.size(15.dp)
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringRes("send_clipboard"),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                ),
                color = if (isLoading) {
                    (if (glass) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.6f)
                } else {
                    if (glass) Color.White else MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun DeviceCard(
    row: DeviceListRow,
    onClick: () -> Unit,
    onRename: (() -> Unit)?,
    onDeviceDetails: (() -> Unit)? = null,
    onSendClipboard: (() -> Unit)? = null,
    onRemove: (() -> Unit)?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    connecting: Boolean = false,
    editMode: Boolean = false,
    dragging: Boolean = false,
    dragHandle: (@Composable () -> Unit)? = null,
    dropDeviceId: String? = null,
    onFilesDropped: ((deviceId: String, paths: List<String>) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    var dropHover by remember { mutableStateOf(false) }
    var cardOriginInWindow by remember { mutableStateOf<Offset?>(null) }
    var titleOverflow by remember { mutableStateOf(false) }
    var subtitleOverflow by remember { mutableStateOf(false) }
    var statOverflow by remember { mutableStateOf(false) }
    val hasOverflow = titleOverflow || subtitleOverflow || statOverflow

    val currentTheme = LocalAppTheme.current
    val allowHoverPopOver = isDesktopHost() && currentTheme.traits.desktopHoverPopOver && !editMode && !dragging && hasOverflow
    val cardInteractionSource = remember { MutableInteractionSource() }
    val isCardHovered by cardInteractionSource.collectIsHoveredAsState()
    val popupInteractionSource = remember { MutableInteractionSource() }
    val isPopupHovered by popupInteractionSource.collectIsHoveredAsState()

    var isHoverDwellActive by remember { mutableStateOf(false) }
    LaunchedEffect(isCardHovered, isPopupHovered) {
        if (isCardHovered || isPopupHovered) {
            if (!isHoverDwellActive) {
                delay(800L)
                isHoverDwellActive = true
            }
        } else {
            isHoverDwellActive = false
        }
    }
    val showPopOver = allowHoverPopOver && isHoverDwellActive

    val popupPositionProvider = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize
            ): IntOffset {
                val origin = cardOriginInWindow
                val cardX = origin?.x?.roundToInt() ?: anchorBounds.left
                val cardY = origin?.y?.roundToInt() ?: anchorBounds.top
                val x = cardX.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val y = cardY.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
                return IntOffset(x, y)
            }
        }
    }

    val dropTargetId = dropDeviceId
    val dropCallback = onFilesDropped
    val dropModifier = if (dropTargetId != null && dropCallback != null) {
        Modifier.deviceFileDropTarget(
            enabled = true,
            onHoverChange = { dropHover = it },
            onFilesDropped = { paths -> dropCallback(dropTargetId, paths) }
        )
    } else {
        Modifier
    }
    val highlighted = selected || dropHover || dragging
    val fluent = LocalFileApexUiStyle.current == DesktopUiStyle.WindowsFluent
    val cleanModern = isFileApexCleanModern()
    val isFlux = currentTheme.traits.fluxSurfaces
    val containerColor = when {
        dragging -> fileApexAccent().copy(alpha = 0.14f)
        dropHover -> fileApexAccent().copy(alpha = 0.22f)
        selected && isFlux -> FluxGlassPalette.navSelectedPill
        selected && cleanModern -> fileApexAccentContainer()
        selected -> fileApexAccent().copy(alpha = 0.12f)
        isCardHovered && cleanModern -> CleanModernPalette.CardHover
        isCardHovered && isFlux -> FluxGlassPalette.cardHover
        cleanModern -> CleanModernPalette.CardSurface
        else -> MaterialTheme.colorScheme.surface
    }
    val liveStats by TransferActivityGuard.statsFlow.collectAsState()
    val pendingItems by if (FileApexServices.isDatabaseReady()) {
        FileApexServices.transferQueue.pendingItems.collectAsState(initial = emptyList())
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    val activeSending = pendingItems.firstOrNull { it.isSending }
    val isSendingToThis = liveStats.isActive && (row.deviceId == dropDeviceId || (activeSending != null && (row.deviceId in activeSending.pendingDeviceIds || activeSending.pendingDeviceNames.any { it.equals(row.title, ignoreCase = true) })))

    val cardShape = if (fluent || cleanModern || isFlux) RoundedCornerShape(10.dp) else RoundedCornerShape(16.dp)

    Box(modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val next = coords.localToWindow(Offset.Zero)
                    if (cardOriginInWindow != next) cardOriginInWindow = next
                }
                .then(if (allowHoverPopOver || cleanModern || isFlux) Modifier.hoverable(cardInteractionSource) else Modifier)
                .then(dropModifier)
                .pressScale(cardInteractionSource).clickable(interactionSource = cardInteractionSource, indication = LocalIndication.current, enabled = !connecting && !editMode, onClick = onClick),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (fluent || highlighted || cleanModern || isFlux) 0.dp else 3.dp
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                highlighted -> if (isFlux) FluxGlassPalette.navSelectedTint else fileApexAccent()
                fluent -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
                cleanModern -> CleanModernPalette.CardBorder
                isFlux -> FluxGlassPalette.cardBorder
                else -> fileApexAccent().copy(alpha = 0.10f)
            }
        )
    ) {
        val hPad = if (cleanModern || isFlux) 12.dp else 16.dp
        val vPad = if (cleanModern || isFlux) 12.dp else 18.dp
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad, vertical = vPad),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceCardIconBadge(row = row, cleanModern = cleanModern, isFlux = isFlux)
            Spacer(modifier = Modifier.width(if (cleanModern || isFlux) 12.dp else 14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { titleOverflow = it.hasVisualOverflow }
                )
                Spacer(modifier = Modifier.height(3.dp))
                if (isSendingToThis) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        val statText = buildList {
                            add(stringRes("sending"))
                            if (liveStats.speedFormatted.isNotBlank()) add(liveStats.speedFormatted)
                            if (liveStats.etaFormatted.isNotBlank()) add(liveStats.etaFormatted)
                            val pct = (liveStats.progress * 100).toInt().coerceIn(0, 100)
                            if (pct in 1..99) add("$pct%")
                        }.joinToString(" • ")
                        Text(
                            text = statText,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = fileApexAccent(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            onTextLayout = { statOverflow = it.hasVisualOverflow }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { liveStats.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = fileApexAccent(),
                            trackColor = fileApexAccent().copy(alpha = 0.2f)
                        )
                    }
                } else if (connecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = fileApexAccent()
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringRes("connecting"),
                            style = MaterialTheme.typography.bodySmall,
                            color = fileApexAccent(),
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                } else {
                    DeviceCaption(
                        row = row,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        onTextLayout = { subtitleOverflow = it.hasVisualOverflow }
                    )
                }
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = stringRes("selected"),
                    tint = fileApexAccent(),
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(22.dp)
                )
            }
            if (editMode && dragHandle != null) {
                dragHandle()
            } else if (onRename != null || onDeviceDetails != null || onSendClipboard != null || onRemove != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.MoreHoriz,
                            contentDescription = stringRes("device_options"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(stringRes("rename")) },
                                onClick = {
                                    menuOpen = false
                                    onRename()
                                }
                            )
                        }
                        if (onDeviceDetails != null) {
                            DropdownMenuItem(
                                text = { Text(deviceMenuText(row, "device_details")) },
                                onClick = {
                                    menuOpen = false
                                    onDeviceDetails()
                                }
                            )
                        }
                        if (onSendClipboard != null && !row.isFriend) {
                            DropdownMenuItem(
                                text = { Text(stringRes("send_clipboard")) },
                                onClick = {
                                    menuOpen = false
                                    onSendClipboard()
                                }
                            )
                        }
                        if (onRemove != null) {
                            DropdownMenuItem(
                                text = { Text(deviceMenuText(row, "remove")) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    if (showPopOver) {
            Popup(
                popupPositionProvider = popupPositionProvider,
                properties = PopupProperties(focusable = false)
            ) {
                DeviceCardPopOver(
                    row = row,
                    connecting = connecting,
                    onClick = onClick,
                    onRename = onRename,
                    onDeviceDetails = onDeviceDetails,
                    onSendClipboard = onSendClipboard,
                    onRemove = onRemove,
                    interactionSource = popupInteractionSource
                )
            }
        }
    }
}

@Composable
private fun DeviceCardIconBadge(row: DeviceListRow, cleanModern: Boolean, isFlux: Boolean) {
    Box {
        DeviceCardIcon(row, cleanModern, isFlux)
        RouteBadge(row, Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp))
    }
}

@Composable
private fun DeviceCardIcon(row: DeviceListRow, cleanModern: Boolean, isFlux: Boolean) {
    val iconStyle = LocalThemeIconStyle.current
    if (cleanModern || (isFlux && iconStyle == ThemeIconStyle.STANDARD)) {
        val shape = RoundedCornerShape(9.dp)
        val bg = if (isFlux) FluxGlassPalette.iconBadgeBg else CleanModernPalette.IconBadgeBg
        val border = if (isFlux) FluxGlassPalette.iconBadgeBorder else CleanModernPalette.IconBadgeBorder
        Box(
            modifier = Modifier.size(38.dp).clip(shape).background(bg).border(1.dp, border, shape),
            contentAlignment = Alignment.Center
        ) {
            DeviceEntryIcon(row = row, modifier = Modifier.size(20.dp))
        }
    } else {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val iconMod = if (deviceIconFillsOrb(iconStyle)) Modifier.fillMaxSize().padding(2.dp) else Modifier.size(24.dp)
            DeviceEntryIcon(row = row, modifier = iconMod, tint = fileApexAccentDark())
        }
    }
}
