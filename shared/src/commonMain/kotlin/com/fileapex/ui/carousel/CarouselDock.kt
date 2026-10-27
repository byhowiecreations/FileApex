package com.fileapex.ui.carousel

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.CarouselPalette
import kotlin.math.abs

enum class DockItem { Home, LocalFiles, Bulletin, Add, Settings }

private val ITEM_SIZE = 44.dp
private val ITEM_GAP = 10.dp
private val DOCK_PAD = 16.dp

@Composable
private fun DockItem.title(): String = when (this) {
    DockItem.Home -> stringRes("home")
    DockItem.LocalFiles -> stringRes("local_files")
    DockItem.Bulletin -> stringRes("bulletin_board")
    DockItem.Add -> stringRes("add_new_device")
    DockItem.Settings -> stringRes("settings")
}

private fun DockItem.icon(): ImageVector = when (this) {
    DockItem.Home -> Icons.Outlined.Home
    DockItem.LocalFiles -> Icons.Outlined.Folder
    DockItem.Bulletin -> Icons.Outlined.Article
    DockItem.Add -> Icons.Outlined.Add
    DockItem.Settings -> Icons.Outlined.Settings
}

/**
 * Floating icon dock. Icons grow toward the pointer like the macOS Dock and show their title while hovered.
 */
@Composable
fun CarouselDock(
    selected: DockItem?,
    unreadCount: Int,
    onHome: () -> Unit,
    onLocalFiles: () -> Unit,
    onBulletin: () -> Unit,
    onGenerateQr: () -> Unit,
    onJoinDevice: () -> Unit,
    onFriends: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = DockItem.entries
    var pointerX by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val centers = remember { mutableStateListOf<Float>().apply { repeat(items.size) { add(0f) } } }

    val targets = items.indices.map { i ->
        pointerX?.let { CarouselMath.dockScale(abs(it - centers[i]), sigmaPx = 70f) } ?: 1f
    }
    val scales = targets.map { animateFloatAsState(it, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMedium)).value }
    val extraPx = scales.map { (it - 1f) * with(androidx.compose.ui.platform.LocalDensity.current) { ITEM_SIZE.toPx() } }
    val growth = CarouselMath.dockPillGrowth(extraPx)
    val shifts = CarouselMath.dockShifts(extraPx)
    val padPx = with(androidx.compose.ui.platform.LocalDensity.current) { DOCK_PAD.toPx() }
    val leftPadPx by rememberUpdatedState(padPx + growth / 2f)
    val hovered = pointerX?.let { x -> items.indices.minByOrNull { abs(x - centers[it]) } }

    Box(modifier = modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
      Box(
          modifier = Modifier
              .padding(bottom = 10.dp)
              .pointerInput(Unit) {
                  awaitPointerEventScope {
                      while (true) {
                          val event = awaitPointerEvent()
                          // The bar widens as icons grow, so positions are taken relative to the icon row, not the bar.
                          pointerX = when (event.type) {
                              PointerEventType.Exit -> null
                              PointerEventType.Move, PointerEventType.Enter -> event.changes.firstOrNull()?.position?.x?.minus(leftPadPx)
                              else -> pointerX
                          }
                      }
                  }
              }
      ) {
        Box(modifier = Modifier.matchParentSize().thawGlass(RoundedCornerShape(26.dp), blur = 40.dp, lift = 0.07f))
        Row(
            modifier = Modifier
                .padding(
                    start = DOCK_PAD + with(androidx.compose.ui.platform.LocalDensity.current) { (growth / 2f).toDp() },
                    end = DOCK_PAD + with(androidx.compose.ui.platform.LocalDensity.current) { (growth / 2f).toDp() },
                    top = 10.dp,
                    bottom = 10.dp
                ),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(ITEM_GAP),
            verticalAlignment = Alignment.Bottom
        ) {
            items.forEachIndexed { i, item ->
                val shift = shifts[i]
                val title = item.title()
                Box(
                    modifier = Modifier
                        .size(ITEM_SIZE)
                        .zIndex(scales[i])
                        .onGloballyPositioned { centers[i] = it.positionInParent().x + it.size.width / 2f }
                        .graphicsLayer { translationX = shift },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(ITEM_SIZE)
                            .graphicsLayer {
                                scaleX = scales[i]
                                scaleY = scales[i]
                                transformOrigin = TransformOrigin(0.5f, 1f)
                            }
                            .thawKey(RoundedCornerShape(13.dp))
                            .semantics { contentDescription = title }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                when (item) {
                                    DockItem.Home -> onHome()
                                    DockItem.LocalFiles -> onLocalFiles()
                                    DockItem.Bulletin -> onBulletin()
                                    DockItem.Add -> menuOpen = true
                                    DockItem.Settings -> onSettings()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = item.icon(),
                            contentDescription = null,
                            tint = if (item == selected) CarouselPalette.accent else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    if (item == selected) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .offset(y = 8.dp)
                                .size(width = 22.dp, height = 2.dp)
                                .background(CarouselPalette.accent, CircleShape)
                        )
                    }
                    if (item == DockItem.Bulletin && unreadCount > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .offset(x = 6.dp, y = (-6).dp)
                                .background(CarouselPalette.accent, CircleShape)
                                .padding(horizontal = 5.dp)
                        ) {
                            Text(
                                text = if (unreadCount > 99) "99+" else unreadCount.toString(),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = CarouselPalette.onAccent
                            )
                        }
                    }
                    if (hovered == i && !menuOpen) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .wrapContentWidth(unbounded = true)
                                .offset(y = (-(ITEM_SIZE.value * (scales[i] - 1f)) - 40).dp)
                                .background(CarouselPalette.cardRaised, RoundedCornerShape(8.dp))
                                .border(BorderStroke(1.dp, CarouselPalette.borderStrong), RoundedCornerShape(8.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(text = title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                        }
                    }
                    if (item == DockItem.Add) {
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringRes("generate_qr")) }, onClick = { menuOpen = false; onGenerateQr() })
                            DropdownMenuItem(text = { Text(stringRes("join_device")) }, onClick = { menuOpen = false; onJoinDevice() })
                            DropdownMenuItem(text = { Text(stringRes("friends_entry")) }, onClick = { menuOpen = false; onFriends() })
                        }
                    }
                }
            }
        }
      }
    }
}
