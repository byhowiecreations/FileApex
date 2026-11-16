package com.fileapex.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fileapex.cloud.currentPlatformLabel
import com.fileapex.di.FileApexServices
import com.fileapex.domain.link.LinkedElsewhere
import com.fileapex.domain.link.PairLink
import com.fileapex.domain.link.PhoneLink
import com.fileapex.domain.link.PhoneLinkClaim
import com.fileapex.domain.notifications.NotificationCompanion
import com.fileapex.domain.notifications.NotificationCompanionGrant
import com.fileapex.domain.peer.PeerPlatform
import com.fileapex.presentation.DeviceListRow
import com.fileapex.util.TimeUtils

/**
 * The device Simple browses and sends to. A chosen device wins; then the partner that is showing this device right now
 * ([followingComputerId], the peer whose announcement this device holds); otherwise a phone prefers an online computer
 * and a computer prefers an online phone, so a pair behaves as one without any setup. A guess skips [excludedIds], the
 * peers that said they are showing another device, unless that would leave nothing to show.
 */
internal fun pickActiveDevice(
    rows: List<DeviceListRow>,
    chosenId: String,
    selfIsPhone: Boolean,
    followingComputerId: String = "",
    excludedIds: Set<String> = emptySet()
): DeviceListRow? {
    rows.firstOrNull { it.deviceId == chosenId }?.let { return it }
    if (followingComputerId.isNotEmpty()) {
        rows.firstOrNull { it.deviceId == followingComputerId }?.let { return it }
    }
    val unclaimed = rows.filter { it.deviceId !in excludedIds }
    // Skipping a taken peer must not swap a phone for another computer: with nobody else of the other kind, use all of them.
    val candidates = if (unclaimed.any { PeerPlatform.isDesktop(it.os, it.platform) == selfIsPhone }) unclaimed else rows
    // Headless Linux servers (Docker) come after real computers.
    val otherKind = candidates
        .filter { PeerPlatform.isDesktop(it.os, it.platform) == selfIsPhone }
        .sortedBy { row -> if (row.os.ifBlank { row.platform }.trim().equals("linux", ignoreCase = true)) 1 else 0 }
    return otherKind.firstOrNull { it.online }
        ?: candidates.firstOrNull { it.online }
        ?: otherKind.firstOrNull()
        ?: candidates.firstOrNull()
}

/**
 * The device Simple Home shows. It also announces this end of the link while Simple Home is on screen: a computer tells
 * the phone it listens to, a phone tells the computer it shows and the one it left.
 */
@Composable
internal fun rememberSimpleActiveDevice(rows: List<DeviceListRow>): DeviceListRow? {
    val settings = FileApexServices.settings
    val storedActiveId by settings.simpleActiveDeviceId.collectAsState()
    val chosenAt by settings.pairLink.chosenAt.collectAsState()
    val companionGrant by settings.pairLink.grant.collectAsState()
    val phoneClaim by settings.pairLink.phoneClaim.collectAsState()
    val selfIsPhone = currentPlatformLabel() == "Android"
    // The partner that announced it is showing this device (a computer's grant on a phone, a phone's link on a computer).
    val now = TimeUtils.now()
    val claim = if (selfIsPhone) NotificationCompanionGrant.currentLink(companionGrant, now) else PhoneLinkClaim.current(phoneClaim, now)
    val link = PairLink.decide(storedActiveId, chosenAt, claim, now)
    val excludedIds = link.excludedIds + LinkedElsewhere.shared.excludedFor(FileApexServices.localIdentity.deviceId)
    val active = pickActiveDevice(rows, link.choiceId, selfIsPhone, link.followId, excludedIds)
    // A choice is announced with its time so the two ends settle a disagreement the same way; a guess carries none.
    val announcedAt = if (active != null && active.deviceId == link.choiceId) chosenAt else 0L

    // The computer listens to notifications from the phone shown here, and only while this screen is up.
    val companionId = active?.takeIf { !selfIsPhone && PeerPlatform.isAndroid(it.os, it.platform) }?.deviceId.orEmpty()
    LaunchedEffect(companionId, announcedAt) { NotificationCompanion.setDesired(companionId, announcedAt) }
    DisposableEffect(Unit) { onDispose { NotificationCompanion.setDesired("") } }

    // The phone tells the computer it shows, and the one it left, while this screen is up.
    val shownComputerId = active?.takeIf { selfIsPhone && PeerPlatform.isDesktop(it.os, it.platform) }?.deviceId.orEmpty()
    LaunchedEffect(shownComputerId, announcedAt) { PhoneLink.setShown(shownComputerId, announcedAt) }
    DisposableEffect(Unit) { onDispose { PhoneLink.setShown("", 0L) } }
    return active
}
