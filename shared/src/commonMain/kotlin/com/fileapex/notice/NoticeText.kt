package com.fileapex.notice

internal object NoticeText {
    const val TITLE_MAX = 80
    const val TEXT_MAX = 600

    private val GITHUB_LINK = "https://github.com/byhowiecreations/FileApex"
    private val PLAY_LINK = "https://play.google.com/store/apps/details?id=com.fileapex"

    /** Plain text only: control characters, zero-width characters and bidi overrides are removed, then the text is cut to [max]. */
    fun clean(raw: String, max: Int, allowNewlines: Boolean): String {
        val out = StringBuilder()
        for (ch in raw) {
            when {
                ch == '\n' && allowNewlines -> out.append(ch)
                ch == '\n' || ch == '\t' -> out.append(' ')
                ch.category == CharCategory.CONTROL -> Unit
                isInvisible(ch) -> Unit
                else -> out.append(ch)
            }
        }
        var text = out.toString().trim()
        if (text.length > max) {
            text = text.substring(0, max)
            if (text.last().isHighSurrogate()) text = text.dropLast(1)
            text = text.trimEnd()
        }
        return text
    }

    fun githubLink(raw: String): String? = raw.takeIf { startsAtBoundary(it, GITHUB_LINK, "/?#") }

    fun playLink(raw: String): String? = raw.takeIf { startsAtBoundary(it, PLAY_LINK, "&#") }

    private fun startsAtBoundary(value: String, prefix: String, boundaries: String): Boolean {
        if (!value.startsWith(prefix) || value.length > 300) return false
        if (value.any { it.isWhitespace() || it.category == CharCategory.CONTROL }) return false
        return value.length == prefix.length || value[prefix.length] in boundaries
    }

    private fun isInvisible(ch: Char): Boolean =
        ch in '​'..'‏' || ch in '‪'..'‮' || ch in '⁠'..'⁤' ||
            ch in '⁦'..'⁩' || ch == '؜' || ch == '﻿'
}
