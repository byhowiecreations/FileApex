package com.fileapex.notice

object NoticeSelector {
    /** Notices that apply to this install, required first and otherwise in file order. */
    fun applicable(payload: NoticePayload, context: NoticeContext): List<Notice> =
        payload.notices.filter { matches(it, context) }.sortedByDescending { it.severity.ordinal }

    fun matches(notice: Notice, context: NoticeContext): Boolean {
        if (NoticeDate.epochDayOf(context.nowEpochMs) >= notice.expiresEpochDay) return false
        if (!NoticeVersionRange.contains(context.version, notice.from, notice.to, notice.below)) return false
        if (notice.platforms != null && context.platform !in notice.platforms) return false
        if (notice.channels != null && context.channel !in notice.channels) return false
        val requires = notice.requires
        if (requires != null && requires.none { it in context.features }) return false
        return true
    }
}
