package com.fileapex.data.device

/**
 * Platform hook to restore an empty roster from a legacy on-disk database when possible.
 */
expect suspend fun recoverEmptyRosterIfNeeded(repository: DeviceRepository)
