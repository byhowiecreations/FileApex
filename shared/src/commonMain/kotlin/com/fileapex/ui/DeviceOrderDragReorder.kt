package com.fileapex.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fileapex.ui.theme.FileApexTeal
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Minimum touch target for the reorder grab handle (Android + desktop pointer). */
private val DragHandleTouchTarget = 48.dp

/** Pixels scrolled per auto-scroll tick while the dragged card hugs a viewport edge. */
private const val ReorderEdgeScrollStepPx = 16f

/** Spring used when non-dragged cards slide to make room during reorder. */
val DeviceOrderItemPlacementSpec: FiniteAnimationSpec<IntOffset> = spring(
    stiffness = Spring.StiffnessMediumLow,
    dampingRatio = Spring.DampingRatioNoBouncy,
    visibilityThreshold = IntOffset(1, 1)
)

/**
 * Live drag state for paired-device reorder (handle-only gesture).
 * List order is not mutated until [end] — only visual offsets change during the drag.
 */
class DeviceOrderDragState {
    var draggingDeviceId: String? by mutableStateOf(null)
    var dragStartIndex by mutableIntStateOf(-1)
    var dragVisualOffsetPx by mutableFloatStateOf(0f)
    private var itemCount: Int = 0

    val isDragging: Boolean get() = draggingDeviceId != null

    fun start(deviceId: String, startIndex: Int, itemCount: Int) {
        draggingDeviceId = deviceId
        dragStartIndex = startIndex
        this.itemCount = itemCount
        dragVisualOffsetPx = 0f
    }

    fun applyStep(deltaY: Float) {
        if (!isDragging) return
        dragVisualOffsetPx += deltaY
    }

    /** Keeps the dragged card under the pointer when edge auto-scroll moves the list. */
    fun applyScrollCompensation(scrollDeltaY: Float) {
        if (!isDragging || scrollDeltaY == 0f) return
        dragVisualOffsetPx += scrollDeltaY
    }

    fun dropIndex(itemStridePx: Float): Int =
        computeDeviceOrderDropIndex(
            startIndex = dragStartIndex,
            dragVisualOffsetPx = dragVisualOffsetPx,
            itemStridePx = itemStridePx,
            itemCount = itemCount
        )

    fun end(itemStridePx: Float, onReorder: (fromIndex: Int, toIndex: Int) -> Unit) {
        val fromIndex = dragStartIndex
        val toIndex = dropIndex(itemStridePx)
        if (fromIndex >= 0 && fromIndex != toIndex) {
            onReorder(fromIndex, toIndex)
        }
        draggingDeviceId = null
        dragStartIndex = -1
        dragVisualOffsetPx = 0f
        itemCount = 0
    }
}

@Composable
fun rememberDeviceOrderDragState(): DeviceOrderDragState = remember { DeviceOrderDragState() }

/**
 * Grab handle (`=`) for paired-device reorder in edit mode.
 * Pointer input is scoped strictly to this handle — the parent card must not receive drag gestures.
 */
@Composable
fun DeviceOrderDragHandle(
    deviceId: String,
    startIndex: Int,
    itemCount: Int,
    dragState: DeviceOrderDragState,
    itemStridePx: Float,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDragging = dragState.draggingDeviceId == deviceId
    Box(
        modifier = modifier
            .sizeIn(minWidth = DragHandleTouchTarget, minHeight = DragHandleTouchTarget)
            .pointerInput(deviceId, startIndex, itemCount) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragState.start(deviceId, startIndex, itemCount)
                    try {
                        drag(down.id) { change ->
                            val deltaY = change.positionChange().y
                            if (deltaY != 0f) {
                                change.consume()
                                dragState.applyStep(deltaY)
                            }
                        }
                    } finally {
                        dragState.end(itemStridePx, onReorder)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = "Drag to reorder",
            tint = if (isDragging) FileApexTeal else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Vertical distance between paired-device card centers while reordering. */
internal fun deviceOrderItemStridePx(
    cardHeight: Dp,
    itemSpacing: Dp,
    density: Density
): Float = with(density) { (cardHeight + itemSpacing).toPx() }

/** Total height of device rows only (excludes trailing list spacer used for the Add button). */
internal fun deviceOrderListItemsHeightPx(
    itemCount: Int,
    cardHeightPx: Float,
    itemSpacingPx: Float,
    topPaddingPx: Float
): Float {
    if (itemCount <= 0) return topPaddingPx
    return topPaddingPx +
        itemCount * cardHeightPx +
        (itemCount - 1).coerceAtLeast(0) * itemSpacingPx
}

/**
 * True when paired-device rows exceed the lazy list viewport — auto-scroll may be needed.
 * Ignores [bottomPaddingPx] so the Add-device spacer does not force scroll during reorder.
 */
internal fun deviceOrderListOverflowsViewport(
    itemCount: Int,
    viewportHeightPx: Int,
    cardHeightPx: Float,
    itemSpacingPx: Float,
    topPaddingPx: Float
): Boolean {
    if (itemCount <= 0 || viewportHeightPx <= 0) return false
    return deviceOrderListItemsHeightPx(itemCount, cardHeightPx, itemSpacingPx, topPaddingPx) >
        viewportHeightPx.toFloat()
}

/**
 * Final drop index from drag start and accumulated pointer offset (half-stride thresholds).
 * Does not mutate list data — call once on drag end.
 */
internal fun computeDeviceOrderDropIndex(
    startIndex: Int,
    dragVisualOffsetPx: Float,
    itemStridePx: Float,
    itemCount: Int
): Int {
    if (startIndex !in 0 until itemCount || itemStridePx <= 0f) return startIndex
    val halfStride = itemStridePx / 2f
    var displacement = 0
    var remainder = dragVisualOffsetPx
    while (remainder > halfStride) {
        if (startIndex + displacement >= itemCount - 1) break
        displacement++
        remainder -= itemStridePx
    }
    while (remainder < -halfStride) {
        if (startIndex + displacement <= 0) break
        displacement--
        remainder += itemStridePx
    }
    return (startIndex + displacement).coerceIn(0, itemCount - 1)
}

/**
 * Per-row visual offset while reordering. Peers slide smoothly as the dragged card crosses
 * half-stride boundaries; the list order stays fixed until the drag ends.
 */
internal fun deviceOrderItemVisualOffsetPx(
    index: Int,
    dragState: DeviceOrderDragState,
    itemCount: Int,
    itemStridePx: Float
): Float {
    if (!dragState.isDragging || itemStridePx <= 0f) return 0f
    val dragIndex = dragState.dragStartIndex
    if (dragIndex !in 0 until itemCount || index !in 0 until itemCount) return 0f
    val dragOffset = dragState.dragVisualOffsetPx
    if (index == dragIndex) return dragOffset

    return when {
        index > dragIndex -> {
            val trigger = (index - dragIndex - 1) * itemStridePx + itemStridePx / 2f
            val overlap = dragOffset - trigger
            if (overlap > 0f) -overlap.coerceAtMost(itemStridePx) else 0f
        }
        else -> {
            val trigger = (dragIndex - index - 1) * itemStridePx + itemStridePx / 2f
            val overlap = -dragOffset - trigger
            if (overlap > 0f) overlap.coerceAtMost(itemStridePx) else 0f
        }
    }
}

internal fun computeDeviceOrderEdgeScrollDelta(
    dragState: DeviceOrderDragState,
    dragIndex: Int,
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    itemStridePx: Float
): Float {
    if (itemStridePx <= 0f || dragIndex < 0) return 0f
    val edgeThreshold = itemStridePx * 0.25f
    val offset = dragState.dragVisualOffsetPx
    return when {
        dragIndex >= lastVisibleIndex && offset > edgeThreshold -> ReorderEdgeScrollStepPx
        dragIndex <= firstVisibleIndex && offset < -edgeThreshold -> -ReorderEdgeScrollStepPx
        else -> 0f
    }
}

/**
 * Auto-scroll only when device rows overflow the viewport AND the dragged card hugs an edge.
 * When all cards fit on screen the list stays completely static during reorder.
 */
@Composable
fun DeviceOrderEdgeAutoScrollEffect(
    dragState: DeviceOrderDragState,
    scrollState: ScrollState,
    deviceIds: List<String>,
    itemStridePx: Float,
    viewportHeightPx: Int,
    listOverflowsViewport: Boolean
) {
    LaunchedEffect(dragState.isDragging, deviceIds, itemStridePx, viewportHeightPx, listOverflowsViewport) {
        if (!dragState.isDragging || !listOverflowsViewport) return@LaunchedEffect
        while (isActive && dragState.isDragging) {
            val dragId = dragState.draggingDeviceId
            if (dragId != null && itemStridePx > 0f) {
                val dragIndex = dragState.dropIndex(itemStridePx)
                if (dragIndex >= 0 && viewportHeightPx > 0) {
                    val scrollOffsetPx = scrollState.value.toFloat()
                    val firstVisible = (scrollOffsetPx / itemStridePx).toInt().coerceIn(0, deviceIds.lastIndex)
                    val visibleCount = (viewportHeightPx / itemStridePx).toInt().coerceAtLeast(1)
                    val lastVisible = (firstVisible + visibleCount - 1).coerceAtMost(deviceIds.lastIndex)
                    val scrollDelta = computeDeviceOrderEdgeScrollDelta(
                        dragState = dragState,
                        dragIndex = dragIndex,
                        firstVisibleIndex = firstVisible,
                        lastVisibleIndex = lastVisible,
                        itemStridePx = itemStridePx
                    )
                    if (scrollDelta != 0f) {
                        scrollState.scroll { scrollBy(scrollDelta) }
                        dragState.applyScrollCompensation(scrollDelta)
                    }
                }
            }
            delay(16L)
        }
    }
}

/**
 * Lazy-list variant — same edge rules as [DeviceOrderEdgeAutoScrollEffect] for [ScrollState].
 */
@Composable
fun DeviceOrderEdgeAutoScrollEffect(
    dragState: DeviceOrderDragState,
    listState: LazyListState,
    deviceIds: List<String>,
    itemStridePx: Float,
    cardHeightPx: Float,
    itemSpacingPx: Float,
    topPaddingPx: Float
) {
    LaunchedEffect(dragState.isDragging, deviceIds, itemStridePx, cardHeightPx, itemSpacingPx, topPaddingPx) {
        if (!dragState.isDragging) return@LaunchedEffect
        while (isActive && dragState.isDragging) {
            val layout = listState.layoutInfo
            val viewportHeight = layout.viewportSize.height
            val overflows = deviceOrderListOverflowsViewport(
                itemCount = deviceIds.size,
                viewportHeightPx = viewportHeight,
                cardHeightPx = cardHeightPx,
                itemSpacingPx = itemSpacingPx,
                topPaddingPx = topPaddingPx
            )
            if (overflows) {
                val dragId = dragState.draggingDeviceId
                if (dragId != null && itemStridePx > 0f) {
                    val dragIndex = dragState.dropIndex(itemStridePx)
                    if (dragIndex >= 0) {
                        val visible = layout.visibleItemsInfo
                        val firstVisible = visible.firstOrNull()?.index ?: dragIndex
                        val lastVisible = visible.lastOrNull()?.index ?: dragIndex
                        val scrollDelta = computeDeviceOrderEdgeScrollDelta(
                            dragState = dragState,
                            dragIndex = dragIndex,
                            firstVisibleIndex = firstVisible,
                            lastVisibleIndex = lastVisible,
                            itemStridePx = itemStridePx
                        )
                        if (scrollDelta != 0f) {
                            listState.scrollBy(scrollDelta)
                            dragState.applyScrollCompensation(scrollDelta)
                        }
                    }
                }
            }
            delay(16L)
        }
    }
}

internal fun deviceOrderDragIntOffset(visualOffsetPx: Float): IntOffset =
    IntOffset(0, visualOffsetPx.roundToInt())
