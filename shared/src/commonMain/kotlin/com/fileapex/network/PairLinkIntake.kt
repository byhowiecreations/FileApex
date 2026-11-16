package com.fileapex.network

import com.fileapex.di.FileApexServices
import com.fileapex.domain.link.HealthSignal
import com.fileapex.domain.link.PairLink
import com.fileapex.domain.link.PhoneLinkClaim
import com.fileapex.domain.notifications.NotificationCompanionGrant
import com.fileapex.domain.peer.PeerPlatform
import io.ktor.server.application.ApplicationCall

/**
 * Any request from a paired computer on mutual TLS may carry `companion` (1 renews this phone's notification grant,
 * 0 stands it down); any request from a paired phone may carry `link` (1 says it shows this computer, 0 that it left),
 * each with the time its user chose that device. Plain HTTP and unpaired senders are ignored.
 */
internal suspend fun FileApexServer.recordPairLink(call: ApplicationCall) {
    recordComputerGrant(call)
    recordPhoneLink(call)
}

private suspend fun FileApexServer.recordComputerGrant(call: ApplicationCall) {
    val signal = PairLink.parse(call.request.queryParameters["companion"], call.request.queryParameters[HealthSignal.CHOSEN_AT]) ?: return
    if (com.fileapex.cloud.currentPlatformLabel() != "Android") return
    val computerId = bridgedPeer(call)?.deviceId ?: return
    val computer = FileApexServices.deviceRepository.getDevice(computerId) ?: return
    if (computer.isRemoved || !PeerPlatform.isDesktop(computer.os, computer.platform)) return
    NotificationCompanionGrant.record(computer.deviceId, signal)
}

private suspend fun FileApexServer.recordPhoneLink(call: ApplicationCall) {
    val signal = PairLink.parse(call.request.queryParameters["link"], call.request.queryParameters[HealthSignal.CHOSEN_AT]) ?: return
    if (com.fileapex.cloud.currentPlatformLabel() == "Android") return
    val phoneId = bridgedPeer(call)?.deviceId ?: return
    val phone = FileApexServices.deviceRepository.getDevice(phoneId) ?: return
    if (phone.isRemoved || !PeerPlatform.isAndroid(phone.os, phone.platform)) return
    PhoneLinkClaim.record(phone.deviceId, signal)
}
