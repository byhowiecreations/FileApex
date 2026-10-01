package com.fileapex.data.device

import com.fileapex.data.settings.AppSettings

internal class SettingsMembershipStore(private val settings: () -> AppSettings) : MembershipStore {
    override var selfVersion: Long
        get() = settings().clusterMembershipVersion.value
        set(value) = settings().setClusterMembershipVersion(value)

    override var protocolSince: Long
        get() = settings().membershipProtocolSinceEpochMs.value
        set(value) = settings().setMembershipProtocolSinceEpochMs(value)
}
