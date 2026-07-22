package com.omninode.update

/**
 * Result of a GitHub Releases update probe (and optional install handoff).
 */
sealed class UpdateCheckOutcome {
    data class AlreadyCurrent(
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
