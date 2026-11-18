package com.fileapex.util

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellableCatchingLogTest {
    private fun captured(block: () -> Unit): String {
        val original = System.out
        val buffer = ByteArrayOutputStream()
        System.setOut(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString()
    }

    @Test fun aFailureIsLoggedWithTheStepAndOnlyTheExceptionClass() {
        val out = captured { cancellableCatching("test-step-a") { throw IOException("/Users/someone/secret.txt 192.168.1.9") } }
        assertTrue(out, out.contains("test-step-a failed - IOException"))
        assertFalse(out, out.contains("secret") || out.contains("192.168"))
    }

    @Test fun theResultIsPassedOnUnchanged() {
        assertEquals(5, cancellableCatching("test-step-b") { 5 }.getOrNull())
        assertEquals(7, cancellableCatching<Int>("test-step-b") { throw IOException() }.getOrDefault(7))
    }

    @Test fun theSameFailureIsLoggedOnceUntilTheStepSucceedsAgain() {
        val out = captured {
            repeat(3) { cancellableCatching("test-step-c") { throw IOException() } }
            cancellableCatching("test-step-c") { 1 }
            cancellableCatching("test-step-c") { throw IOException() }
        }
        assertEquals(out, 2, Regex("test-step-c failed").findAll(out).count())
    }

    @Test fun aDifferentFailureOfTheSameStepIsLoggedAtOnce() {
        val out = captured {
            cancellableCatching("test-step-d") { throw IOException() }
            cancellableCatching("test-step-d") { throw IllegalStateException() }
        }
        assertEquals(out, 2, Regex("test-step-d failed").findAll(out).count())
    }

    @Test fun aStepWithoutANameIsNotLogged() {
        val out = captured { cancellableCatching { throw IOException() } }
        assertTrue(out, out.isEmpty())
    }

    @Test fun cancellationIsNeverLogged() {
        val out = captured {
            try {
                cancellableCatching("test-step-e") { throw kotlinx.coroutines.CancellationException("stop") }
            } catch (_: kotlinx.coroutines.CancellationException) {
            }
        }
        assertFalse(out, out.contains("test-step-e"))
    }
}
