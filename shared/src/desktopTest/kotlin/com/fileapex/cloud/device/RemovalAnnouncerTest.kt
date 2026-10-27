package com.fileapex.cloud.device

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovalAnnouncerTest {
    @Test
    fun readsThePushAddressThenTellsTheRelayThenPushes() = runBlocking {
        val steps = mutableListOf<String>()
        RemovalAnnouncer(
            pushTokenOf = { steps += "read $it"; "fcm-token" },
            revoke = { steps += "revoke" },
            push = { steps += "push $it"; true }
        ).announce("fx-gone")
        assertEquals(listOf("read fx-gone", "revoke", "push fcm-token"), steps)
    }

    @Test
    fun aDeviceWithNoPushAddressStillGetsRevoked() = runBlocking {
        val steps = mutableListOf<String>()
        RemovalAnnouncer(pushTokenOf = { null }, revoke = { steps += "revoke" }, push = { steps += "push"; true }).announce("fx-gone")
        assertEquals(listOf("revoke"), steps)
    }
}
