package com.fileapex.data.db.seal

import java.security.SecureRandom
import org.junit.Assert.*
import org.junit.Test

class FieldSealerTest {
    private fun key() = ByteArray(32).also { SecureRandom().nextBytes(it) }
    private val sealer = FieldSealer(key())

    @Test fun roundTrips() {
        val stored = sealer.seal("messages", "content", "hello é中")
        assertTrue(stored.startsWith(FieldSealer.PREFIX))
        assertFalse(stored.contains("hello"))
        assertEquals("hello é中", sealer.open("messages", "content", stored))
    }

    @Test fun sameTextSealsDifferentlyEachTime() {
        assertNotEquals(sealer.seal("messages", "content", "same"), sealer.seal("messages", "content", "same"))
    }

    @Test fun emptyStaysEmptyAndPlainPassesThrough() {
        assertEquals("", sealer.seal("messages", "content", ""))
        assertEquals("", sealer.open("messages", "content", ""))
        assertEquals("legacy text", sealer.open("messages", "content", "legacy text"))
    }

    @Test fun aValueCannotMoveToAnotherColumn() {
        val stored = sealer.seal("messages", "content", "secret")
        assertNull(sealer.open("messages", "senderName", stored))
        assertNull(sealer.open("checklist_items", "text", stored))
    }

    @Test fun aWrongKeyOrATamperedValueIsNotOpened() {
        val stored = sealer.seal("messages", "content", "secret")
        assertNull(FieldSealer(key()).open("messages", "content", stored))
        val tampered = stored.dropLast(3) + if (stored.endsWith("AAA")) "BBB" else "AAA"
        assertNull(sealer.open("messages", "content", tampered))
        assertNull(sealer.open("messages", "content", FieldSealer.PREFIX + "not base64 !!"))
        assertNull(sealer.open("messages", "content", FieldSealer.PREFIX + "AAAA"))
    }

    @Test fun onlyA32ByteKeyIsAccepted() {
        assertThrows(IllegalArgumentException::class.java) { FieldSealer(ByteArray(16)) }
    }

    @Test fun isSealedRecognisesThePrefix() {
        assertTrue(sealer.isSealed(sealer.seal("a", "b", "x")))
        assertFalse(sealer.isSealed("x"))
        assertFalse(sealer.isSealed(""))
    }
}
