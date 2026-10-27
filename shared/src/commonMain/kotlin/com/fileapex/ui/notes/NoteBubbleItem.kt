package com.fileapex.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.note.NoteRecord
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.stringRes
import com.fileapex.platform.BriefToast
import com.fileapex.platform.usesDesktopFileSelection
import com.fileapex.platform.PlatformClipboard
import com.fileapex.ui.BrandCoolBlue
import com.fileapex.ui.BulletinBubbleContainer
import com.fileapex.ui.ExplorerEntryIcons
import com.fileapex.ui.IosModernApexBlue
import com.fileapex.ui.NoteLinkText
import com.fileapex.ui.theme.FileApexTeal
import com.fileapex.util.TimeUtils
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NoteBubbleItem(
    item: NoteRecord,
    cardBg: Color,
    textColor: Color,
    subTextColor: Color,
    isCustomGlass: Boolean,
    revealed: Boolean,
    highlighted: Boolean = false,
    assembling: Boolean,
    thumbnail: ImageBitmap?,
    isDownloadingAttachment: Boolean = false,
    footerLabel: String? = null,
    gesturesEnabled: Boolean = true,
    style: BulletinBoardStyle = BulletinBoardStyle.DEFAULT,
    onBubblePositioned: (LayoutCoordinates) -> Unit,
    onRevealedChange: (Boolean) -> Unit,
    onCloseAnyReveal: () -> Unit,
    onDeleteClick: () -> Unit,
    onLockClick: () -> Unit,
    checklist: ChecklistActions? = null,
    onOpenAttachment: () -> Unit,
    onHashtagClick: ((String) -> Unit)? = null
) {
    val isMine = item.isMine
    val alignment = if (isMine) Alignment.End else Alignment.Start
    val effectiveTextColor = when (style) {
        BulletinBoardStyle.STICKY_NOTE, BulletinBoardStyle.TORN_LEDGER -> Color(0xFF1E2022)
        BulletinBoardStyle.IOS_MODERN -> if (isMine) Color.White else textColor
        BulletinBoardStyle.AERO_GLASS -> Color.White
        else -> textColor
    }
    val effectiveSubTextColor = when (style) {
        BulletinBoardStyle.STICKY_NOTE, BulletinBoardStyle.TORN_LEDGER -> Color(0xFF555960)
        BulletinBoardStyle.IOS_MODERN -> if (isMine) Color.White.copy(alpha = 0.85f) else subTextColor
        BulletinBoardStyle.AERO_GLASS -> Color.White.copy(alpha = 0.75f)
        else -> subTextColor
    }
    val effectiveHeaderColor = when (style) {
        BulletinBoardStyle.DEFAULT -> if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
        BulletinBoardStyle.STICKY_NOTE -> Color(0xFFB71C1C)
        BulletinBoardStyle.TORN_LEDGER -> Color(0xFF1E5BB0)
        BulletinBoardStyle.IOS_MODERN -> if (isMine) Color.White else IosModernApexBlue
        BulletinBoardStyle.MATERIAL_YOU -> BrandCoolBlue
        BulletinBoardStyle.AERO_GLASS -> Color(0xFF4ADE80)
    }
    val stamp = TimeUtils.noteListStamp(item.epochMs)
    val attachmentName = item.attachmentFileName?.ifBlank { null }
    val hasAttachment = !attachmentName.isNullOrBlank()
    val caption = item.content.trim().takeIf { text ->
        text.isNotEmpty() && text != attachmentName && !text.startsWith("[Synced note from Drive:")
    }

    val copyableText = caption ?: attachmentName ?: item.content.trim()
    val hasCopyable = copyableText.isNotBlank()
    val density = LocalDensity.current
    val bubbleScope = rememberCoroutineScope()
    val actionCount = (if (hasCopyable) 1 else 0) + 2
    val actionWidthPx = with(density) { (52.dp * actionCount).toPx() }
    val offsetAnim = remember { Animatable(0f) }
    val showActions = revealed || offsetAnim.value < -1f
    var menuOpen by remember { mutableStateOf(false) }
    val snackbar = LocalNotesSnackbar.current
    val desktop = usesDesktopFileSelection()

    LaunchedEffect(revealed, actionWidthPx) {
        offsetAnim.animateTo(if (revealed) -actionWidthPx else 0f)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        if (showActions) {
            NoteRevealActions(
                copyableText = copyableText.takeIf { hasCopyable },
                locked = item.attachmentPinned,
                isCustomGlass = isCustomGlass,
                onCloseAnyReveal = onCloseAnyReveal,
                onLockClick = onLockClick,
                onDeleteClick = onDeleteClick,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        NoteContextMenu(
            expanded = menuOpen,
            actions = NoteMenuActions.forNote(hasCopyable, item.attachmentPinned),
            onDismiss = { menuOpen = false },
            onAction = { runNoteMenuAction(it, copyableText, snackbar, onLockClick, onDeleteClick) }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (assembling) 0f else 1f },
            horizontalAlignment = alignment
        ) {
            val isPinnedPinStyle = item.attachmentPinned &&
                (style == BulletinBoardStyle.STICKY_NOTE || style == BulletinBoardStyle.TORN_LEDGER)
            Column(
                modifier = Modifier
                    .widthIn(min = 90.dp, max = 380.dp)
                    .wrapContentWidth()
                    .offset { IntOffset(offsetAnim.value.roundToInt(), 0) }
                    .onRightClick(enabled = desktop && gesturesEnabled) { menuOpen = true }
                    .then(
                        if (gesturesEnabled) {
                            Modifier
                                .draggable(
                                    state = rememberDraggableState { delta ->
                                        val next = (offsetAnim.value + delta).coerceIn(-actionWidthPx, 0f)
                                        bubbleScope.launch { offsetAnim.snapTo(next) }
                                    },
                                    orientation = Orientation.Horizontal,
                                    onDragStopped = {
                                        val open = offsetAnim.value < -actionWidthPx / 2f
                                        onRevealedChange(open)
                                        bubbleScope.launch {
                                            offsetAnim.animateTo(if (open) -actionWidthPx else 0f)
                                        }
                                    }
                                )
                        } else {
                            Modifier
                        }
                    )
            ) {
                BulletinBubbleContainer(
                    style = style,
                    isMine = isMine,
                    cardBg = cardBg,
                    isCustomGlass = isCustomGlass,
                    highlighted = highlighted,
                    isPinned = item.attachmentPinned,
                    modifier = Modifier
                        .onGloballyPositioned(onBubblePositioned)
                        .padding(top = if (isPinnedPinStyle) 8.dp else 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .bubbleTaps(gesturesEnabled, revealed, onRevealedChange, onCloseAnyReveal, copyableText.takeIf { hasCopyable }, snackbar)
                            .padding(
                                horizontal = if (style == BulletinBoardStyle.TORN_LEDGER) 14.dp else 12.dp,
                                vertical = if (style == BulletinBoardStyle.TORN_LEDGER) 14.dp else 12.dp
                            )
                    ) {
                    if (!item.driveFileId.isNullOrBlank()) {
                        Surface(shape = RoundedCornerShape(4.dp), color = Color(0x3300E5FF)) {
                            Text(
                                text = stringRes("drive_sync"),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                                color = Color(0xFF00E5FF),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                    if (!caption.isNullOrBlank()) {
                        if (!item.driveFileId.isNullOrBlank()) Spacer(modifier = Modifier.height(6.dp))
                        NoteLinkText(
                            text = caption,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, lineHeight = 19.sp),
                            color = effectiveTextColor,
                            linkColor = effectiveHeaderColor,
                            onHashtagClick = onHashtagClick
                        )
                    }
                    if (item.contentType == com.fileapex.data.bulletin.BulletinContentType.CHECKLIST) {
                        ChecklistLines(item.checklist, checklist, effectiveTextColor, effectiveHeaderColor, gesturesEnabled)
                    }
                    if (attachmentName != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (thumbnail != null) {
                            Surface(
                                onClick = {
                                    if (gesturesEnabled) {
                                        onCloseAnyReveal()
                                        onOpenAttachment()
                                    }
                                },
                                enabled = gesturesEnabled,
                                shape = RoundedCornerShape(8.dp),
                                color = if (isCustomGlass) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ) {
                                NoteAttachmentThumbnail(thumbnail, attachmentName, effectiveTextColor)
                            }
                        } else {
                            Surface(
                                onClick = {
                                    if (gesturesEnabled) {
                                        onCloseAnyReveal()
                                        onOpenAttachment()
                                    }
                                },
                                enabled = gesturesEnabled,
                                shape = RoundedCornerShape(6.dp),
                                color = if (isCustomGlass) {
                                    Color.White.copy(alpha = 0.08f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = ExplorerEntryIcons.iconForFile(attachmentName, ""),
                                        contentDescription = stringRes("open_attachment"),
                                        tint = effectiveSubTextColor,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = attachmentName,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = effectiveTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
            val footerText = footerLabel ?: "${stamp.timeLabel} (${if (isMine) stringRes("this_device") else item.sourceDeviceName})"
            if (footerText.isNotEmpty() || isDownloadingAttachment) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (footerText.isNotEmpty()) {
                        Text(
                            text = footerText,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = subTextColor
                        )
                    }
                    if (isDownloadingAttachment) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                        )
                        Text(
                            text = stringRes("downloading"),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = subTextColor
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
internal fun NoteRevealAction(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    onClick: () -> Unit
) {
    // The touch target is the full 52 x 56 cell; the circle drawn in it stays 44 dp.
    Box(
        modifier = Modifier.size(width = 52.dp, height = 56.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Surface(shape = CircleShape, color = containerColor, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}
