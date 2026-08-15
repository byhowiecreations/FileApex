package com.fileapex.cloud.drive

const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/**
 * User Drive OAuth ([DRIVE_FILE_SCOPE] only — files FileApex creates). Tokens refresh only
 * when a Drive call returns 401, never on a timer.
 */
expect object GoogleDriveAuth {
    fun hasGrant(): Boolean

    /** Tokens on disk for Drive, whether or not a live API probe has succeeded. */
    fun hasStoredAccess(): Boolean

    suspend fun accessToken(): String

    /** Exchange a just-granted authorization code / tokens from the platform UI flow. */
    suspend fun persistGrant(accessToken: String, refreshToken: String, expiresAtEpochMs: Long)

    /** Set after a live Drive API probe succeeds. [hasGrant] stays false until this runs. */
    fun markAccessVerified()

    /**
     * Called after HTTP 401. Returns true when a new access token was obtained.
     */
    suspend fun refreshOnUnauthorized(): Boolean

    fun clearGrant()
}
