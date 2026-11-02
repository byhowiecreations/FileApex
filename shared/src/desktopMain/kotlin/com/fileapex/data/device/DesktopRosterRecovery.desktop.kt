package com.fileapex.data.device

/**
 * Unsigned leftover roster files have no authority. Membership is the signed chain only.
 * Schema 27 already migrated the live database; this path must not re-admit peers.
 */
internal object DesktopRosterRecovery {
    suspend fun importLegacyRosterIfEmpty(repository: DeviceRepository) {
        if (repository.listDevices().isNotEmpty()) return
    }
}
