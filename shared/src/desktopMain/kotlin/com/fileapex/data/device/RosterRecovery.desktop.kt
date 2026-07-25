package com.fileapex.data.device

actual suspend fun recoverEmptyRosterIfNeeded(repository: DeviceRepository) {
    DesktopRosterRecovery.importLegacyRosterIfEmpty(repository)
}
