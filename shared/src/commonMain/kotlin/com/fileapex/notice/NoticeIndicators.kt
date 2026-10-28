package com.fileapex.notice

sealed interface NoticeUpdateTarget {
    data object InApp : NoticeUpdateTarget
    data class Listing(val url: String) : NoticeUpdateTarget
}

/** A notice that applies to this install right now, with whether it was already seen. */
data class VisibleNotice(val notice: Notice, val seen: Boolean)

object NoticeIndicators {
    /** True while an `important` notice is unseen or any `required` notice applies: the red "!" on the Bulletin Board icon. */
    fun showsAlert(visible: List<VisibleNotice>): Boolean =
        visible.any { it.notice.severity == NoticeSeverity.Required || (it.notice.severity == NoticeSeverity.Important && !it.seen) }

    /** Unseen `info` notices add to the normal count. */
    fun plainCount(visible: List<VisibleNotice>): Int = visible.count { it.notice.severity == NoticeSeverity.Info && !it.seen }

    /** Opening the Bulletin Board counts the quiet `info` entries as seen; the others clear when their entry is opened. */
    fun idsSeenByListing(visible: List<VisibleNotice>): List<String> =
        visible.filter { !it.seen && it.notice.severity == NoticeSeverity.Info }.map { it.notice.id }

    /** GitHub builds update themselves; store builds are only ever sent to their own listing. */
    fun updateTarget(channel: NoticeChannel): NoticeUpdateTarget = when (channel) {
        NoticeChannel.Github -> NoticeUpdateTarget.InApp
        NoticeChannel.Play -> NoticeUpdateTarget.Listing("https://play.google.com/store/apps/details?id=com.fileapex")
        NoticeChannel.Fdroid -> NoticeUpdateTarget.Listing("https://f-droid.org/packages/com.fileapex/")
    }

    /** The Play build only ever links to its own listing, never to the GitHub APK. */
    fun linkFor(notice: Notice, channel: NoticeChannel): String? = if (channel == NoticeChannel.Play) notice.linkPlay else notice.link

    /** `required` cannot be removed; `info` and `important` can. */
    fun canDismiss(notice: Notice): Boolean = notice.severity != NoticeSeverity.Required

    /** Dismissed `info` stays hidden; dismissed `important` comes back after [REAPPEAR_MS]. */
    fun isHiddenByDismissal(notice: Notice, dismissedAtMs: Long?, nowMs: Long): Boolean = when {
        dismissedAtMs == null -> false
        notice.severity == NoticeSeverity.Required -> false
        notice.severity == NoticeSeverity.Important -> nowMs - dismissedAtMs < REAPPEAR_MS
        else -> true
    }

    const val REAPPEAR_MS = 7L * 24 * 60 * 60 * 1000
}
