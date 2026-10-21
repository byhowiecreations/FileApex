package com.fileapex.domain.clipboard

import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.data.db.isDockerNode

/**
 * Which peers take the newer ciphertext format, which authenticates sender, recipient and context. Decrypting accepts
 * both formats at all times; a message is written in the newer one only for a peer known to be on this version or
 * later, so a cluster with older devices keeps working. Docker nodes stay on the older format until they are updated.
 */
object PeerE2eeVersions {
    /** The first version code that reads the newer format. */
    const val MIN_VERSION_CODE = 211

    @Volatile
    private var capable: Set<String> = emptySet()

    @Volatile
    private var sealedNotes: Set<String> = emptySet()

    fun update(devices: List<PairedDeviceEntity>) {
        capable = devices.filter { !it.isRemoved && !it.isDockerNode() && it.clientVersionCode >= MIN_VERSION_CODE }
            .map { it.deviceId }.toSet()
        sealedNotes = devices.filter { !it.isRemoved && !it.isDockerNode() && it.clientVersionCode >= com.fileapex.cloud.NotePushCrypto.MIN_VERSION_CODE }
            .map { it.deviceId }.toSet()
    }

    fun supportsBoundFormat(peerDeviceId: String): Boolean = peerDeviceId.trim() in capable

    /** Peers on 0.19.5 or later read Bulletin Board pushes whose content is sealed end to end. */
    fun supportsSealedNotes(peerDeviceId: String): Boolean = peerDeviceId.trim() in sealedNotes
}
