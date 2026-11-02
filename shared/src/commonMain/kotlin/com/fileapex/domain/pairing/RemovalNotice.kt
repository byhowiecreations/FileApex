package com.fileapex.domain.pairing

import com.fileapex.data.db.PairedDeviceEntity

/**
 * Why a device was removed, chosen where the removal is made. [USER_REMOVED] is the Remove button on a roster device and
 * the only reason a removed device acts on; [LEFT_CLUSTER] is a device leaving on its own from Settings; [SUPERSEDED] is an automatic
 * clean-up of an outdated copy. Every reason but [USER_REMOVED] is only logged as a hint.
 */
enum class RemovalReason(val code: String) {
    USER_REMOVED("user_removed"),
    SUPERSEDED("superseded"),
    LEFT_CLUSTER("left_cluster")
}

sealed interface RemovalVerdict {
    data object Act : RemovalVerdict
    data object NotForThisDevice : RemovalVerdict
    data class Hint(val why: String) : RemovalVerdict
}

object RemovalNotice {
    fun build(removed: PairedDeviceEntity, clusterVersion: Long, reason: RemovalReason, removerId: String) = RemovedDeviceRecord(
        deviceId = removed.deviceId,
        publicKeyHash = removed.publicKeyHash,
        lastKnownIp = removed.lastKnownIp,
        port = removed.port,
        clusterVersion = clusterVersion,
        removedAt = clusterVersion,
        membershipProtocol = 0,
        reason = reason.code,
        removedBy = removerId
    )

    /**
     * A removal naming this device is believed only when the reason says the user pressed Remove and the sender is a
     * TLS-authenticated roster member (the remover itself or a roster device passing its notice on: the reason is set
     * where the removal is made, so a relay keeps it). Anything else cannot be validated and stays a hint.
     */
    fun judge(
        record: RemovedDeviceRecord,
        localDeviceId: String,
        authenticatedSenderId: String?,
        senderIsRosterMember: Boolean,
        senderCanCommand: Boolean = true
    ): RemovalVerdict {
        if (record.deviceId != localDeviceId) return RemovalVerdict.NotForThisDevice
        val sender = authenticatedSenderId?.trim().orEmpty()
        return when {
            sender.isEmpty() -> RemovalVerdict.Hint("the sender was not authenticated")
            !senderIsRosterMember -> RemovalVerdict.Hint("the sender is not in the roster")
            !senderCanCommand -> RemovalVerdict.Hint("the sender cannot command membership")
            record.reason != RemovalReason.USER_REMOVED.code ->
                RemovalVerdict.Hint("reason ${record.reason.ifBlank { "none given (no reason)" }} cannot be validated")
            else -> RemovalVerdict.Act
        }
    }

    fun logPeerRemoval(record: RemovedDeviceRecord) {
        println(
            "PairingCoordinator: ${record.deviceId} removed, reason ${record.reason.ifBlank { "none" }}, " +
                "by ${record.removedBy.ifBlank { "unknown" }}"
        )
    }

    /** Logs the reason and what was done with it. True when the removal was believed and acted on. */
    fun handleSelf(
        record: RemovedDeviceRecord,
        localDeviceId: String,
        authenticatedSenderId: String,
        senderName: String?,
        senderIsRosterMember: Boolean
    ): Boolean {
        val verdict = judge(record, localDeviceId, authenticatedSenderId, senderIsRosterMember)
        println(
            "PairingCoordinator: removal of this device, reason ${record.reason.ifBlank { "none" }}, " +
                "removed by ${record.removedBy.ifBlank { "unknown" }}, sent by ${authenticatedSenderId.ifEmpty { "unauthenticated" }} " +
                "(v=${record.membershipVersion()}): " +
                when (verdict) {
                    RemovalVerdict.Act -> "acting on it"
                    RemovalVerdict.NotForThisDevice -> "not for this device"
                    is RemovalVerdict.Hint -> "hint only, ${verdict.why}"
                }
        )
        if (verdict == RemovalVerdict.Act) {
            KickedByPeer.onUserRemoved(senderName.orEmpty(), record.membershipVersion())
            return true
        }
        com.fileapex.cloud.device.DeviceIdentity.onKicked()
        return false
    }
}
