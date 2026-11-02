package com.fileapex.domain.pairing

import kotlinx.serialization.Serializable

@Serializable
data class RemovedDeviceRecord(
    val deviceId: String,
    val publicKeyHash: String = "",
    val lastKnownIp: String = "",
    val port: Int = 0,
    val clusterVersion: Long = 0L,
    val removedAt: Long? = null,
    val membershipProtocol: Int = 0,
    val reason: String = "",
    val removedBy: String = ""
) {
    fun membershipVersion(): Long = maxOf(clusterVersion, removedAt ?: 0L)
}
