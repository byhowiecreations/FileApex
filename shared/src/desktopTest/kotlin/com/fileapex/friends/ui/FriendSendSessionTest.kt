package com.fileapex.friends.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FriendSendSessionTest {
    @After
    fun clear() {
        FriendSendSession.clear()
    }

    @Test
    fun aRequestIsClaimedOnceByTheFirstFileScreen() {
        FriendSendSession.begin(FriendSendTarget("rome", "Rome"))
        assertEquals(FriendSendTarget("rome", "Rome"), FriendSendSession.pending.value)
        FriendSendSession.clear()
        assertNull(FriendSendSession.pending.value)
    }

    @Test
    fun aNewRequestReplacesAnUnclaimedOne() {
        FriendSendSession.begin(FriendSendTarget("rome", "Rome"))
        FriendSendSession.begin(FriendSendTarget("oslo", "Oslo"))
        assertEquals("oslo", FriendSendSession.pending.value?.key)
    }
}
