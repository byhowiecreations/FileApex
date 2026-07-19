package com.omninode.presentation

import androidx.compose.runtime.Immutable

/**
 * Stable list-cell model for the devices LazyColumn.
 *
 * Mirrors a [DiffUtil.ItemCallback] contract used with ListAdapter / AsyncListDiffer:
 * - [areItemsTheSame] → permanent entity key ([deviceId])
 * - [areContentsTheSame] → deep structural equality of displayed fields
 */
@Immutable
data class DeviceListRow(
    val deviceId: String,
    val deviceName: String,
    val lastKnownIp: String,
    val port: Int,
    val online: Boolean
) {
    val title: String get() = deviceName

    val subtitle: String
        get() = if (online) {
            "Online · $lastKnownIp:$port"
        } else {
            "Offline · $lastKnownIp:$port"
        }

    companion object {
        fun areItemsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean =
            oldItem.deviceId == newItem.deviceId

        fun areContentsTheSame(oldItem: DeviceListRow, newItem: DeviceListRow): Boolean =
            oldItem == newItem
    }
}
