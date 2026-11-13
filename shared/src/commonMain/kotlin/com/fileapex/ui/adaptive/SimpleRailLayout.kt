package com.fileapex.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.fileapex.ui.adaptive.simple.SimpleRailCapsulePaddingPolicy
import kotlin.math.max

enum class SimpleRailEntry { LocalFiles, Browse, Clipboard, BulletinBoard, DeviceManagement, Settings }

/** Which popover the rail buttons open. */
enum class SimplePopover { DeviceManagement, More }

/** Entries that stay on the rail first when space runs out. */
private val keepOrder = listOf(
    SimpleRailEntry.LocalFiles,
    SimpleRailEntry.Browse,
    SimpleRailEntry.BulletinBoard,
    SimpleRailEntry.Settings,
    SimpleRailEntry.Clipboard,
    SimpleRailEntry.DeviceManagement
)

/**
 * Entries that do not fit in [available] pixels and move into the More popover. The More button
 * takes a slot of its own, so it is reserved whenever anything has to be hidden.
 */
internal fun hiddenRailEntries(
    heights: Map<SimpleRailEntry, Int>,
    moreHeight: Int,
    available: Int
): Set<SimpleRailEntry> {
    if (heights.values.sum() <= available) return emptySet()
    var used = 0
    val budget = available - moreHeight
    val visible = mutableSetOf<SimpleRailEntry>()
    for (entry in keepOrder) {
        val height = heights[entry] ?: continue
        if (used + height > budget) break
        used += height
        visible += entry
    }
    return heights.keys - visible
}

internal class RailSlot(val entry: SimpleRailEntry, val content: @Composable () -> Unit)

/**
 * Lays the rail items out at their real sizes. When they do not all fit, the lowest priority ones are
 * dropped and [more] is shown instead; [onHiddenChanged] reports which ones so the popover can list them.
 */
@Composable
internal fun AdaptiveRailColumn(
    slots: List<RailSlot>,
    more: @Composable () -> Unit,
    onHiddenChanged: (Set<SimpleRailEntry>) -> Unit,
    modifier: Modifier = Modifier,
    upperCapsule: (@Composable () -> Unit)? = null
) {
    var reported: Set<SimpleRailEntry>? = null
    SubcomposeLayout(modifier = modifier) { constraints ->
        val topPadPx = if (upperCapsule != null) SimpleRailCapsulePaddingPolicy.topPaddingDp.dp.roundToPx() else 0
        val bottomPadPx = if (upperCapsule != null) SimpleRailCapsulePaddingPolicy.bottomPaddingDp.dp.roundToPx() else 0
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val placeables = slots.associate { slot ->
            slot.entry to subcompose(slot.entry) { slot.content() }.map { it.measure(loose) }
        }
        val morePlaceables = subcompose("more") { more() }.map { it.measure(loose) }
        val heights = placeables.mapValues { (_, list) -> list.sumOf { it.height } }
        val moreHeight = morePlaceables.sumOf { it.height }
        val available = if (constraints.hasBoundedHeight) {
            (constraints.maxHeight - topPadPx - bottomPadPx).coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        val hidden = hiddenRailEntries(heights, moreHeight, available)
        if (hidden != reported) {
            reported = hidden
            onHiddenChanged(hidden)
        }
        val overflow = hidden.isNotEmpty()
        val shown = slots.map { it.entry }.filter { it !in hidden }
        val upperEntries = shown.filter { it != SimpleRailEntry.Settings || overflow }
        val widest = max(
            shown.maxOfOrNull { entry -> placeables.getValue(entry).maxOfOrNull { it.width } ?: 0 } ?: 0,
            if (overflow) morePlaceables.maxOfOrNull { it.width } ?: 0 else 0
        )
        val width = max(widest, constraints.minWidth).coerceAtMost(constraints.maxWidth)
        val upperContentHeight = upperEntries.sumOf { heights.getValue(it) } + (if (overflow) moreHeight else 0)
        val capsuleHeight = if (upperCapsule != null && upperContentHeight > 0) {
            (upperContentHeight + topPadPx + bottomPadPx).coerceAtMost(
                if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
            )
        } else {
            0
        }
        val capsulePlaceables = if (upperCapsule != null && capsuleHeight > 0) {
            subcompose("capsule") { upperCapsule() }.map {
                it.measure(Constraints.fixed(width, capsuleHeight))
            }
        } else {
            emptyList()
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else heights.values.sum() + topPadPx + bottomPadPx
        layout(width, height) {
            capsulePlaceables.forEach { it.placeRelative(0, 0) }
            var y = topPadPx
            fun place(list: List<androidx.compose.ui.layout.Placeable>, at: Int) {
                var offset = at
                list.forEach { p ->
                    p.placeRelative((width - p.width) / 2, offset)
                    offset += p.height
                }
            }
            upperEntries.forEach { entry ->
                val list = placeables.getValue(entry)
                place(list, y)
                y += list.sumOf { it.height }
            }
            if (overflow) {
                place(morePlaceables, y)
            } else if (SimpleRailEntry.Settings in shown) {
                val list = placeables.getValue(SimpleRailEntry.Settings)
                place(list, height - list.sumOf { it.height })
            }
        }
    }
}
