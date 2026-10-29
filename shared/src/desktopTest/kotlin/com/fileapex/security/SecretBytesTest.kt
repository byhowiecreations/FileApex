package com.fileapex.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SecretBytesTest {
    @Test
    fun theBytesAreZeroAfterTheBlockReturns() {
        val secret = byteArrayOf(1, 2, 3, 4)
        val seen = secret.useAndWipe { it.sum() }
        assertEquals(10, seen)
        assertArrayEquals(ByteArray(4), secret)
    }

    @Test
    fun theBytesAreZeroWhenTheBlockThrows() {
        val secret = byteArrayOf(9, 9, 9)
        try {
            secret.useAndWipe { error("boom") }
            fail("must rethrow")
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(ByteArray(3), secret)
    }

    @Test
    fun aNullSecretRunsNothing() {
        val none: ByteArray? = null
        assertEquals("skipped", none.useAndWipeOr("skipped") { "ran" })
    }
}
