package com.fileapex.ui.devices

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.vector.ImageVector
import com.fileapex.i18n.stringRes

internal enum class FreestyleMenuAction(val key: String, val shortLabel: String, val fullLabelRes: String, val icon: ImageVector) {
    BROWSE("browse", "Browse", "browse", Icons.Filled.FolderOpen),
    CLIPBOARD("clipboard", "Clip", "clipboard", Icons.Filled.ContentPaste),
    INFO("info", "Details", "details", Icons.Filled.Info),
    RENAME("rename", "Rename", "rename", Icons.Filled.Edit),
    REMOVE("remove", "Remove", "remove", Icons.Filled.Delete);

    companion object {
        fun parseOrder(orderStr: String?): List<FreestyleMenuAction> {
            if (orderStr.isNullOrBlank()) return entries
            val tokens = orderStr.split(",").map { it.trim().lowercase() }
            val resolved = tokens.mapNotNull { token -> entries.firstOrNull { it.key == token } }
            val missing = entries.filterNot { resolved.contains(it) }
            return (resolved + missing)
        }

        fun encodeOrder(actions: List<FreestyleMenuAction>): String =
            actions.joinToString(",") { it.key }
    }
}

internal enum class FreestyleOptionAction(val key: String, val icon: ImageVector) {
    FILES("files", Icons.Filled.Folder),
    ADD_DEVICE("add_device", Icons.Filled.QrCode),
    JOIN_DEVICE("join_device", Icons.Filled.Devices),
    FRIENDS("friends", Icons.Filled.People),
    /** The Groups tile: listed only once a friend exists. */
    GROUPS("groups", Icons.Filled.Groups),
    SEND_CLIPBOARD("send_clipboard", Icons.Filled.ContentPaste),
    CHECK_BATTERIES("check_batteries", Icons.Filled.BatteryChargingFull),
    SETTINGS("settings", Icons.Filled.Settings);

    companion object {
        val DEFAULT_ORDER = listOf(
            FILES,
            ADD_DEVICE,
            JOIN_DEVICE,
            FRIENDS,
            GROUPS,
            SEND_CLIPBOARD,
            CHECK_BATTERIES,
            SETTINGS
        )

        fun parseOrder(raw: String?): List<FreestyleOptionAction> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val tokens = raw.split(",").map { it.trim().lowercase() }
            val byKey = entries.associateBy { it.key }
            val parsed = tokens.mapNotNull { byKey[it] }.distinct()
            val missing = DEFAULT_ORDER.filter { it !in parsed }
            return parsed + missing
        }

        fun encodeOrder(list: List<FreestyleOptionAction>): String =
            list.joinToString(",") { it.key }
    }
}

@Composable
internal fun FreestyleOptionAction.label(): String = when (this) {
    FreestyleOptionAction.FILES -> "Files"
    FreestyleOptionAction.ADD_DEVICE -> stringRes("add_new_device")
    FreestyleOptionAction.JOIN_DEVICE -> stringRes("join_device")
    FreestyleOptionAction.FRIENDS -> stringRes("friends_entry")
    FreestyleOptionAction.GROUPS -> stringRes("groups_title")
    FreestyleOptionAction.SEND_CLIPBOARD -> stringRes("send_clipboard")
    FreestyleOptionAction.CHECK_BATTERIES -> stringRes("check_batteries")
    FreestyleOptionAction.SETTINGS -> stringRes("settings")
}

internal class OptionSwap(val order: List<FreestyleOptionAction>, val index: Int, val offsetY: Float)

/**
 * Moving a dragged Options row more than half a row up or down swaps it with its neighbor in the visible list.
 * [visible] must be the current menu (the order minus pinned entries), not one captured when the drag began.
 */
internal fun swapOptionsDuringDrag(
    order: List<FreestyleOptionAction>,
    visible: List<FreestyleOptionAction>,
    currentIdx: Int,
    offsetY: Float,
    rowHeightPx: Float
): OptionSwap? {
    val step = when {
        offsetY > rowHeightPx * 0.5f && currentIdx < visible.lastIndex -> 1
        offsetY < -rowHeightPx * 0.5f && currentIdx > 0 -> -1
        else -> return null
    }
    if (currentIdx !in visible.indices) return null
    val from = visible[currentIdx]
    val to = visible[currentIdx + step]
    val fromAt = order.indexOf(from)
    val toAt = order.indexOf(to)
    if (fromAt == -1 || toAt == -1) return null
    val swapped = order.toMutableList()
    swapped[fromAt] = to
    swapped[toAt] = from
    return OptionSwap(swapped, currentIdx + step, offsetY - step * rowHeightPx)
}
