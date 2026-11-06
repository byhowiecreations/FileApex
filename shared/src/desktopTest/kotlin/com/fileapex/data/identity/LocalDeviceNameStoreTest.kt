package com.fileapex.data.identity

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class LocalDeviceNameStoreTest {
    private var reads = 0
    private var name = "motorola motorola signature"
    private var readable = false

    private fun identity() = LocalIdentity(
        deviceId = "fx-1", deviceName = name, rootPath = "/", deviceNameReadable = readable
    )

    @Before
    fun setUp() {
        LocalDeviceNameStore.resetForTest { reads++; identity() }
    }

    @After
    fun tearDown() = LocalDeviceNameStore.resetForTest { identity() }

    @Test
    fun aNameReadWhileTheKeyIsLockedShowsTheFallbackButIsNotKeptForTheRun() {
        assertEquals("motorola motorola signature", LocalDeviceNameStore.current())
        name = "moto signature"; readable = true
        assertEquals("moto signature", LocalDeviceNameStore.current())
        assertEquals("moto signature", LocalDeviceNameStore.deviceName.value)
    }

    @Test
    fun everyCallRetriesUntilTheNameCouldBeRead() {
        LocalDeviceNameStore.current(); LocalDeviceNameStore.current(); LocalDeviceNameStore.current()
        assertEquals(3, reads)
    }

    @Test
    fun onceTheNameWasReadItIsKeptAndNotReadAgain() {
        name = "moto signature"; readable = true
        repeat(4) { LocalDeviceNameStore.current() }
        assertEquals(1, reads)
    }

    @Test
    fun aNameTheUserSetSettlesTheStoreAtOnce() {
        LocalDeviceNameStore.settleWith("Kitchen Tablet")
        repeat(3) { assertEquals("Kitchen Tablet", LocalDeviceNameStore.current()) }
        assertEquals(0, reads)
    }
}
