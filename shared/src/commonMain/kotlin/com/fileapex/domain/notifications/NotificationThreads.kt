package com.fileapex.domain.notifications

/** One person or chat (messages, email) or one app (everything else), with every notification behind it. */
data class NotificationThread(
    val id: String,
    val kind: NotificationKind,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val lines: List<String>,
    val keys: List<String>,
    val replyKey: String?,
    val latestAtEpochMs: Long
)

object NotificationThreads {
    private const val MAX_LINES = 12

    /** Newest thread first. Messages and email group by sender, everything else by app. */
    fun threads(items: List<NotificationPayload>, kind: NotificationKind): List<NotificationThread> {
        val ofKind = items.filter { it.kind == kind }
        val grouped = if (kind == NotificationKind.OTHER) {
            ofKind.groupBy { it.packageName }
        } else {
            ofKind.groupBy { it.packageName + "\u0000" + conversationOf(it).lowercase() }
        }
        return grouped.map { (id, group) ->
            val newestFirst = group.sortedByDescending { it.postedAtEpochMs }
            val newest = newestFirst.first()
            NotificationThread(
                id = id,
                kind = kind,
                packageName = newest.packageName,
                appLabel = newest.appLabel,
                title = if (kind == NotificationKind.OTHER) newest.appLabel else conversationOf(newest),
                lines = newestFirst.reversed().flatMap(::linesOf).distinct().takeLast(MAX_LINES),
                keys = group.map { it.key },
                replyKey = newestFirst.firstOrNull { it.canReply }?.key,
                latestAtEpochMs = newest.postedAtEpochMs
            )
        }.sortedByDescending { it.latestAtEpochMs }
    }

    private fun conversationOf(item: NotificationPayload): String =
        item.conversation.ifBlank { item.title }.ifBlank { item.appLabel }

    private fun linesOf(item: NotificationPayload): List<String> = when {
        item.lines.isNotEmpty() -> item.lines
        item.kind == NotificationKind.OTHER -> listOf(listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString(": "))
        else -> listOf(item.text)
    }.filter { it.isNotBlank() }
}
