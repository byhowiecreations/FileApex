package com.fileapex.ui.adaptive

import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.presentation.DeviceListRow

/**
 * The device Simple browses and sends to. A chosen device wins; otherwise a phone prefers an online
 * computer and a computer prefers an online phone, so a pair behaves as one without any setup.
 */
internal fun pickActiveDevice(
    rows: List<DeviceListRow>,
    chosenId: String,
    selfIsPhone: Boolean
): DeviceListRow? {
    rows.firstOrNull { it.deviceId == chosenId }?.let { return it }
    // Headless Linux servers (Docker) come after real computers.
    val otherKind = rows
        .filter { PeerPlatform.isDesktop(it.os, it.platform) == selfIsPhone }
        .sortedBy { row -> if (row.os.ifBlank { row.platform }.trim().equals("linux", ignoreCase = true)) 1 else 0 }
    return otherKind.firstOrNull { it.online }
        ?: rows.firstOrNull { it.online }
        ?: otherKind.firstOrNull()
        ?: rows.firstOrNull()
}
