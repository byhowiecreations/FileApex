package com.fileapex.friends

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FriendPathTrackerTest {
    private var info: FriendPathInfo? = null
    private var now = 1_000L
    private var hide = false
    private var connectFails = false
    private val tracker = FriendPathTracker(
        infoOf = { info },
        connect = { if (connectFails) throw IOException("no route") },
        hideIp = { hide },
        now = { now }
    )
    private val lan = FriendPathInfo(relay = false, local = true, rttMs = 3)
    private val internet = FriendPathInfo(relay = false, local = false, rttMs = 40)
    private val relay = FriendPathInfo(relay = true, local = false, rttMs = 90)

    private fun statusOf(key: String = "f") = runBlocking { tracker.refresh(listOf(key)); tracker.paths.value[key] }
    private fun pathOf(key: String = "f") = statusOf(key)?.path

    @Test fun aFriendWeHaveNotReachedYetIsBeingChecked() {
        assertEquals(FriendPath.CHECKING, pathOf())
    }

    @Test fun aFriendIsShownAsCheckingWhileTheConnectionIsStillBeingTried() = runBlocking {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = FriendPathTracker(infoOf = { null }, connect = { release.await() }, hideIp = { false }, now = { now })
        val refreshing = async(kotlinx.coroutines.Dispatchers.Default) { slow.refresh(listOf("f")) }
        kotlinx.coroutines.withTimeout(5_000L) { while (slow.paths.value["f"]?.path != FriendPath.CHECKING) kotlinx.coroutines.delay(10) }
        release.complete(Unit)
        refreshing.await()
    }

    @Test fun aConnectionThatOpensButNeverShowsAPathStopsBeingCheckedOnTheSecondLook() {
        assertEquals(FriendPath.CHECKING, pathOf())
        assertEquals(FriendPath.UNREACHABLE, pathOf())
        info = lan
        assertEquals(FriendPath.DIRECT_LAN, pathOf())
    }

    @Test fun aDirectPathOnTheSameNetworkIsNamedThatAndKeepsItsRoundTrip() {
        info = lan
        assertEquals(FriendPathStatus(FriendPath.DIRECT_LAN, 3), statusOf())
    }

    @Test fun aDirectPathThroughTheInternetIsNamedThatToo() {
        info = internet
        assertEquals(FriendPathStatus(FriendPath.DIRECT_INTERNET, 40), statusOf())
    }

    @Test fun aRelayPathIsJustRelayAtFirstThenCouldNotGoDirect() {
        info = relay
        assertEquals(FriendPath.RELAY, pathOf())
        now += 9_000
        assertEquals(FriendPath.RELAY, pathOf())
        now += 2_000
        assertEquals(FriendPath.RELAY_NO_DIRECT, pathOf())
    }

    @Test fun aLateUpgradeToDirectWinsAndTheWaitStartsOverIfItFallsBack() {
        info = relay; pathOf(); now += 20_000
        assertEquals(FriendPath.RELAY_NO_DIRECT, pathOf())
        info = lan
        assertEquals(FriendPath.DIRECT_LAN, pathOf())
        info = relay
        assertEquals(FriendPath.RELAY, pathOf())
    }

    @Test fun hidingTheIpAddressMakesTheRelayExpectedNotAFailure() {
        info = relay; hide = true; pathOf(); now += 60_000
        assertEquals(FriendPath.RELAY_HIDDEN, pathOf())
    }

    @Test fun aFriendWeCannotConnectToIsUnreachable() {
        connectFails = true
        assertEquals(FriendPath.UNREACHABLE, pathOf())
    }

    @Test fun removedFriendsAreForgotten() {
        info = lan; pathOf("a")
        runBlocking { tracker.refresh(listOf("b")) }
        assertEquals(setOf("b"), tracker.paths.value.keys)
    }
}
