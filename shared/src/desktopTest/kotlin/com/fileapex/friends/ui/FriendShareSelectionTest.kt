package com.fileapex.friends.ui

import com.fileapex.friends.transfer.FriendSharedQueueException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FriendShareSelectionTest {
    @Test fun retryOnlyQueuesFriendsThatWereNotAcceptedAndDelaysDeviceDispatch() = runBlocking {
        val attempts = mutableListOf<Set<String>>()
        var devices = 0
        var finishes = 0
        val share = FriendShareSelection(this) { keys, _ -> attempts += keys; if (attempts.size == 1) setOf("a") else keys }
        share.selected = setOf("a", "b")
        share.send({ listOf(File("example.txt")) }, true, { devices++ }, { finishes++ })
        assertEquals(setOf("b"), share.selected)
        assertTrue(share.failed)
        assertFalse(share.sending)
        assertEquals(0, devices)
        share.send({ listOf(File("example.txt")) }, true, { devices++ }, { finishes++ })
        assertEquals(listOf(setOf("a", "b"), setOf("b")), attempts)
        assertEquals(1, devices)
        assertEquals(0, finishes)
        assertFalse(share.failed)
    }

    @Test fun aStagingFailureRetainsPartialAcceptanceForRetry() = runBlocking {
        val share = FriendShareSelection(this) { _, _ -> throw FriendSharedQueueException(setOf("a"), IOException("test staging failure")) }
        share.selected = setOf("a", "b")
        var finished = false
        share.send({ emptyList() }, false, {}, { finished = true })
        assertEquals(setOf("b"), share.selected)
        assertTrue(share.failed)
        assertFalse(share.sending)
        assertFalse(finished)
    }

    @Test fun repeatedPressWhileStagingDoesNotDuplicateTheAttempt() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var finishes = 0
        val share = FriendShareSelection(this) { keys, _ -> attempts++; started.complete(Unit); release.await(); keys }
        share.selected = setOf("a")
        val first = launch { share.send({ emptyList() }, false, {}, { finishes++ }) }
        withTimeout(5_000L) { started.await() }
        share.send({ emptyList() }, false, {}, { finishes++ })
        release.complete(Unit)
        withTimeout(5_000L) { first.join() }
        assertEquals(1, attempts)
        assertEquals(1, finishes)
    }

    @Test fun theFriendListComesFromItsSourceWithoutBeingAssigned() = runBlocking {
        val alex = com.fileapex.friends.data.FriendLinkEntity("k1", "Alex", "Friends", 1, "Alex")
        var current = listOf(alex)
        val share = FriendShareSelection(this, friendsSource = { current }) { keys, _ -> keys }
        assertEquals(listOf(alex), share.friends)
        current = emptyList()
        assertTrue(share.friends.isEmpty())
    }
}
