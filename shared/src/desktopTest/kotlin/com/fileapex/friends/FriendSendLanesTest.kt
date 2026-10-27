package com.fileapex.friends

import com.fileapex.friends.transfer.FriendSendLanes
import com.fileapex.friends.transfer.FriendsSendQueue
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class FriendSendLanesTest {
    private val dir = Files.createTempDirectory("lanes").toFile().also { it.deleteOnExit() }
    private fun item(id: String, size: Int, friend: String = "f") =
        FriendsSendQueue.Item(id, friend, File(dir, id).also { it.writeBytes(ByteArray(size)) })

    private fun setup(vararg items: FriendsSendQueue.Item, max: Int = 4): Pair<FriendsSendQueue, FriendSendLanes> {
        val q = FriendsSendQueue(); items.forEach { q.add(it) }
        return q to FriendSendLanes(q, maxLanes = max, largeBytes = 1000)
    }

    @Test fun startsAsManyLanesAsFilesUpToTheLimit() {
        val (_, l) = setup(*Array(6) { item("s$it", 10) })
        assertEquals(4, l.lanesToStart("f"))
        assertEquals(0, l.lanesToStart("f"))
    }

    @Test fun fewFilesStartFewLanes() {
        val (_, l) = setup(item("a", 10), item("b", 10))
        assertEquals(2, l.lanesToStart("f"))
    }

    @Test fun onlyOneLargeFileGoesAtOnceWhileSmallOnesPassIt() {
        val (_, l) = setup(item("big1", 2000), item("big2", 2000), item("small", 10))
        l.lanesToStart("f")
        assertEquals("big1", l.next("f")!!.id)
        assertEquals("small", l.next("f")!!.id)
        assertNull(l.next("f"))
    }

    @Test fun nextLargeFileStartsOnceTheFirstIsFinished() {
        val (_, l) = setup(item("big1", 2000), item("big2", 2000))
        l.lanesToStart("f")
        val first = l.next("f")!!
        assertNull(l.next("f"))
        l.finished("f", first)
        assertEquals("big2", l.next("f")!!.id)
    }

    @Test fun friendsDoNotShareLanesOrTheLargeFileRule() {
        val (_, l) = setup(item("a", 2000, "x"), item("b", 2000, "y"))
        l.lanesToStart("x"); l.lanesToStart("y")
        assertEquals("a", l.next("x")!!.id)
        assertEquals("b", l.next("y")!!.id)
    }

    @Test fun lanesAreFreedWhenTheQueueEmpties() {
        val (q, l) = setup(item("a", 10))
        assertEquals(1, l.lanesToStart("f"))
        assertEquals("a", l.next("f")!!.id)
        assertNull(l.next("f"))
        q.add(item("b", 10))
        assertEquals(1, l.lanesToStart("f"))
    }
}
