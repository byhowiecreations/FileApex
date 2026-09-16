package com.fileapex.cli

import com.fileapex.cli.commands.CliCommandHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class CliQueueParserTest {

    @Test
    fun testParseSingleIndex() {
        val indices = CliCommandHandler.parseIndexRange("1", 5)
        assertEquals(listOf(1), indices)
    }

    @Test
    fun testParseRange() {
        val indices = CliCommandHandler.parseIndexRange("2-4", 5)
        assertEquals(listOf(2, 3, 4), indices)
    }

    @Test
    fun testOutOfBoundsIndex() {
        val indices = CliCommandHandler.parseIndexRange("6", 5)
        assertEquals(emptyList<Int>(), indices)
    }

    @Test
    fun testInvalidRange() {
        val indices = CliCommandHandler.parseIndexRange("4-2", 5)
        assertEquals(emptyList<Int>(), indices)
    }

    @Test
    fun testMalformedRange() {
        val indices = CliCommandHandler.parseIndexRange("abc", 5)
        assertEquals(emptyList<Int>(), indices)
    }
}
