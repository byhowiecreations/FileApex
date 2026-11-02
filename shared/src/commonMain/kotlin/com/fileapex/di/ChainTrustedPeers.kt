package com.fileapex.di

import com.fileapex.data.db.MemberEffectiveEntity
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.membership.TrustedPeer

/** The peers this device already trusts. Only one known to lack the chain may be admitted without a key; a peer that speaks it, or whose build is not known yet, joins with its own key. */
internal fun trustedPeersOf(members: List<MemberEffectiveEntity>, devices: List<PairedDeviceEntity>, selfId: String): List<TrustedPeer> =
    members
        .filter { it.state == "ACTIVE" && it.tlsPin.length == 64 && it.deviceId != selfId }
        .map { member ->
            val raw = devices.firstOrNull { it.deviceId == member.deviceId }?.supportedProtocolsJson?.trim().orEmpty()
            val knownOld = raw.isNotEmpty() && raw != "[]" &&
                com.fileapex.domain.peer.PeerNodeProtocols.knownToLackChain(com.fileapex.domain.peer.PeerNodeStateMapper.decodeProtocols(raw))
            TrustedPeer(member.deviceId, member.publicKey, member.tlsPin, mayBeLegacy = knownOld)
        }
