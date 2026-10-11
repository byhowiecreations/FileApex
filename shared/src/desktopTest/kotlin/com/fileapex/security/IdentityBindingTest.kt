package com.fileapex.security

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityBindingTest {
    private fun key(seed: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { (it + seed).toByte() })

    @Test
    fun derivedIdIsStableAndTiedToTheKey() {
        val id = IdentityBinding.deriveDeviceId(key(1))
        assertEquals(id, IdentityBinding.deriveDeviceId(key(1)))
        assertNotEquals(id, IdentityBinding.deriveDeviceId(key(2)))
        assertTrue(IdentityBinding.isDerived(id))
        assertTrue(IdentityBinding.matches(id, key(1)))
        assertFalse(IdentityBinding.matches(id, key(2)))
    }

    @Test
    fun randomIdsFromEarlierBuildsAreNotDerived() {
        val legacy = "9c483b1c-db88-4aaa-9472-7884bd779e0d"
        assertFalse(IdentityBinding.isDerived(legacy))
        assertFalse(IdentityBinding.matches(legacy, key(1)))
    }

    @Test
    fun hashIsTheKeyDigestForDerivedIdsAndTheIdFingerprintOtherwise() {
        val id = IdentityBinding.deriveDeviceId(key(3))
        assertEquals(IdentityBinding.keyHash(key(3)), IdentityBinding.hashFor(id, key(3)))
        assertEquals(16, IdentityBinding.keyHash(key(3)).length)
        val legacy = "legacy-id"
        assertEquals(
            com.fileapex.util.DeviceIdentityMarkers.fingerprint(legacy),
            IdentityBinding.hashFor(legacy, key(3))
        )
    }

    @Test
    fun resolveKeepsALegacyIdAndPersistsNothing() {
        var persisted: String? = null
        val resolved = LocalIdentityBinding.resolve("old-random-id", { "unused" }) { persisted = it }
        assertEquals("old-random-id", resolved)
        assertEquals(null, persisted)
    }
}
