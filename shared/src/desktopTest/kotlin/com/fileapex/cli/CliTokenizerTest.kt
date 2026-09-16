package com.fileapex.cli

import org.junit.Assert.assertEquals
import org.junit.Test

class CliTokenizerTest {

    @Test
    fun testBasicTokenization() {
        val tokens = CliTokenizer.tokenize("send moto-razr hello.txt")
        assertEquals(listOf("send", "moto-razr", "hello.txt"), tokens)
    }

    @Test
    fun testPreservesDoubleQuotedMultiWordDeviceName() {
        val tokens = CliTokenizer.tokenize("alias \"moto razr fold 2026\" razr")
        assertEquals(listOf("alias", "moto razr fold 2026", "razr"), tokens)
    }

    @Test
    fun testPreservesDoubleQuotedMessageWithSpaces() {
        val tokens = CliTokenizer.tokenize("send razr \"Hello world this is a test message\"")
        assertEquals(listOf("send", "razr", "Hello world this is a test message"), tokens)
    }

    @Test
    fun testSingleQuotes() {
        val tokens = CliTokenizer.tokenize("send 'my device' 'a path with spaces/file.png'")
        assertEquals(listOf("send", "my device", "a path with spaces/file.png"), tokens)
    }

    @Test
    fun testEscapedCharacters() {
        val tokens = CliTokenizer.tokenize("send my\\ device message\\ with\\ spaces")
        assertEquals(listOf("send", "my device", "message with spaces"), tokens)
    }

    @Test
    fun testNormalizeArgsWithPreservedOuterQuotes() {
        val args = arrayOf("\"moto razr fold 2026\"", "razr")
        val normalized = CliTokenizer.normalizeArgs(args)
        assertEquals(listOf("moto razr fold 2026", "razr"), normalized)
    }

    @Test
    fun testNormalizeSingleStringWrapperLaunch() {
        val args = arrayOf("send \"moto razr fold 2026\" \"Hello world\"")
        val normalized = CliTokenizer.normalizeArgs(args)
        assertEquals(listOf("send", "moto razr fold 2026", "Hello world"), normalized)
    }
}
