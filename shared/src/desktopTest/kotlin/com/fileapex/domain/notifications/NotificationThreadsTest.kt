package com.fileapex.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationThreadsTest {
    private fun note(
        key: String,
        kind: NotificationKind,
        pkg: String = "p",
        conversation: String = "",
        title: String = "",
        text: String = "",
        at: Long = 0,
        reply: Boolean = false
    ) = NotificationPayload(
        key = key, packageName = pkg, appLabel = pkg.uppercase(), title = title, text = text,
        kind = kind, postedAtEpochMs = at, conversation = conversation, canReply = reply
    )

    @Test
    fun sameSenderIsOneThreadWithAllItsKeys() {
        val items = listOf(
            note("1", NotificationKind.MESSAGE, conversation = "Ana", text = "hi", at = 1),
            note("2", NotificationKind.MESSAGE, conversation = "ana", text = "there", at = 2, reply = true),
            note("3", NotificationKind.MESSAGE, conversation = "Bo", text = "yo", at = 3)
        )
        val threads = NotificationThreads.threads(items, NotificationKind.MESSAGE)
        assertEquals(listOf("Bo", "ana"), threads.map { it.title })
        val ana = threads.last()
        assertEquals(listOf("1", "2"), ana.keys.sorted())
        assertEquals(listOf("hi", "there"), ana.lines)
        assertEquals("2", ana.replyKey)
    }

    @Test
    fun sameSenderInDifferentAppsStaysSeparate() {
        val items = listOf(
            note("1", NotificationKind.MESSAGE, pkg = "a", conversation = "Ana", text = "x"),
            note("2", NotificationKind.MESSAGE, pkg = "b", conversation = "Ana", text = "y")
        )
        assertEquals(2, NotificationThreads.threads(items, NotificationKind.MESSAGE).size)
    }

    @Test
    fun otherGroupsByAppAndFallsBackToTitleAndText() {
        val items = listOf(
            note("1", NotificationKind.OTHER, pkg = "x", title = "Sale", text = "50%", at = 1),
            note("2", NotificationKind.OTHER, pkg = "x", title = "Sale 2", text = "", at = 2),
            note("3", NotificationKind.OTHER, pkg = "y", title = "Hi", at = 3)
        )
        val threads = NotificationThreads.threads(items, NotificationKind.OTHER)
        assertEquals(listOf("Y", "X"), threads.map { it.title })
        assertEquals(listOf("Sale: 50%", "Sale 2"), threads.last().lines)
    }

    @Test
    fun messageWithoutConversationUsesTitle() {
        val items = listOf(note("1", NotificationKind.EMAIL, title = "Boss", text = "Report"))
        assertEquals("Boss", NotificationThreads.threads(items, NotificationKind.EMAIL).single().title)
    }
}
