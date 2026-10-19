package com.fileapex.iroh

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class IrohLogLabelTest {
    @After
    fun reset() {
        IrohLog.labeler = { null }
    }

    @Test
    fun unknownIdsShowTheirFirstEightCharacters() {
        assertEquals("3f4a10b5", IrohLog.short("3f4a10b5" + "0".repeat(56)))
    }

    @Test
    fun knownIdsShowTheDeviceNameWithTheKey() {
        IrohLog.labeler = { id -> if (id.startsWith("3f4a10b5")) "MacBook Pro" else null }

        assertEquals("MacBook Pro (3f4a10b5)", IrohLog.short("3f4a10b5" + "0".repeat(56)))
        assertEquals("6e689ef0", IrohLog.short("6e689ef0" + "0".repeat(56)))
    }
}
