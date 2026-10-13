package com.fileapex.cloud.drive

actual object GoogleDriveAuth {
    actual fun hasGrant(): Boolean = false

    actual fun hasStoredAccess(): Boolean = false

    actual suspend fun accessToken(): String =
        throw UnsupportedOperationException("Google Drive is not part of the F-Droid build")

    actual suspend fun persistGrant(accessToken: String, refreshToken: String, expiresAtEpochMs: Long) = Unit

    actual fun markAccessVerified() = Unit

    actual suspend fun refreshOnUnauthorized(): Boolean = false

    actual fun clearGrant() = Unit
}
