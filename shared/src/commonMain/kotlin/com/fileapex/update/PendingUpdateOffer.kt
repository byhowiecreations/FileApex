package com.fileapex.update

import com.fileapex.i18n.AppI18n

/**
 * A newer FileApex build detected on GitHub Releases, awaiting user action on Android
 * or immediate install on desktop.
 */
data class PendingUpdateOffer(
    val remoteVersion: String,
    val releaseTitle: String?,
    val releaseNotes: String?,
    val assetName: String,
    val assetDownloadUrl: String,
    val assetSizeBytes: Long
) {
    fun notificationDetail(maxNoteLines: Int = 6): String {
        val title = releaseTitle?.trim()?.takeIf { it.isNotEmpty() }
        val notes = releaseNotes?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(maxNoteLines)
            ?.joinToString(separator = "\n")
        return buildString {
            if (!title.isNullOrBlank() && title != remoteVersion) {
                append(title)
            }
            if (!notes.isNullOrBlank()) {
                if (isNotEmpty()) append('\n')
                append(notes)
            }
            if (isEmpty()) {
                append(AppI18n.t("update_review_notes"))
            }
        }
    }
}
