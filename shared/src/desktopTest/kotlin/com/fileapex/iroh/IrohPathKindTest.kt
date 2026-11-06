package com.fileapex.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrohPathKindTest {
    private val custom = "https://relay.getfileapex.com"

    @Test
    fun noSelectedPathMeansNoAnswer() {
        assertNull(IrohPathKind.of(emptyList(), custom))
    }

    @Test
    fun anIpPathIsDirect() {
        assertEquals("direct", IrohPathKind.of(listOf(IrohPathKind.Selected(isRelay = false, remoteAddr = "192.168.1.5:5000")), custom))
    }

    @Test
    fun aRelayPathOnTheSelfHostedRelayIsTheCustomRelay() {
        val path = IrohPathKind.Selected(isRelay = true, remoteAddr = "https://relay.getfileapex.com/")
        assertEquals("custom_relay", IrohPathKind.of(listOf(path), custom))
        assertEquals("custom_relay", IrohPathKind.of(listOf(path), "https://relay.getfileapex.com:443"))
    }

    @Test
    fun aRelayPathOnAnotherRelayIsPlainRelay() {
        val path = IrohPathKind.Selected(isRelay = true, remoteAddr = "https://use1-1.relay.n0.iroh.link./")
        assertEquals("relay", IrohPathKind.of(listOf(path), custom))
        assertEquals("relay", IrohPathKind.of(listOf(path), null))
    }

    @Test
    fun aRelayPathWithoutAnAddressIsPlainRelayNeverGuessed() {
        assertEquals("relay", IrohPathKind.of(listOf(IrohPathKind.Selected(isRelay = true, remoteAddr = "")), custom))
    }

    @Test
    fun aRelayPathAmongSelectedPathsMakesItRelay() {
        val paths = listOf(IrohPathKind.Selected(false, "10.0.0.1:1"), IrohPathKind.Selected(true, "https://relay.getfileapex.com/"))
        assertEquals("custom_relay", IrohPathKind.of(paths, custom))
    }

    @Test
    fun theLogNamesEveryPathInPlainWords() {
        assertEquals("direct", IrohPathKind.logLabel("direct"))
        assertEquals("relay", IrohPathKind.logLabel("relay"))
        assertEquals("custom relay", IrohPathKind.logLabel("custom_relay"))
        assertEquals("no live path", IrohPathKind.logLabel(null))
        assertEquals("no live path", IrohPathKind.logLabel("something else"))
    }
}
