package com.fileapex.update

/**
 * Result of a GitHub Releases update probe (and optional install handoff).
 */
sealed class UpdateCheckOutcome {
    data class AlreadyCurrent(
        val localVersion: String,
        val latestTag: String
    ) : UpdateCheckOutcome()

    /** The running build is newer than the latest GitHub release (a test build ahead of the release page). */
    data class NewerThanRelease(
        val localVersion: String,
        val latestTag: String
    ) : UpdateCheckOutcome()

    data class Available(
        val offer: PendingUpdateOffer
    ) : UpdateCheckOutcome()

    data class Installing(
        val remoteVersion: String,
        val releaseTitle: String?,
        val releaseNotes: String?
    ) : UpdateCheckOutcome()
}
