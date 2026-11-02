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
        val resolved = LocalIdentityBinding.resolve("old-random-id", { "unused" }, persist = { persisted = it })
        assertEquals("old-random-id", resolved)
        assertEquals(null, persisted)
    }

    @Test
    fun aDerivedIdThatNoLongerMatchesTheKeyIsReplacedAndTheOldOneIsRemembered() {
        val oldId = IdentityBinding.deriveDeviceId(key(1))
        val newId = IdentityBinding.deriveDeviceId(key(2))
        var persisted: String? = null
        val remembered = mutableListOf<String>()
        val resolved = LocalIdentityBinding.resolve(oldId, { "unused" }, { persisted = it }, { remembered += it }) { key(2) }
        assertEquals(newId, resolved)
        assertEquals(newId, persisted)
        assertEquals(listOf(oldId), remembered)
    }

    @Test
    fun anIdThatStillMatchesItsKeyChangesAndRemembersNothing() {
        val id = IdentityBinding.deriveDeviceId(key(1))
        val remembered = mutableListOf<String>()
        var persisted: String? = null
        assertEquals(id, LocalIdentityBinding.resolve(id, { "unused" }, { persisted = it }, { remembered += it }) { key(1) })
        assertEquals(null, persisted)
        assertTrue(remembered.isEmpty())
    }

    @Test
    fun aFirstStartHasNothingToRemember() {
        val remembered = mutableListOf<String>()
        val resolved = LocalIdentityBinding.resolve(null, { "unused" }, {}, { remembered += it }) { key(4) }
        assertEquals(IdentityBinding.deriveDeviceId(key(4)), resolved)
        assertTrue(remembered.isEmpty())
    }

    @Test
    fun aKeyThatCannotBeReadRightNowChangesNothing() {
        val id = IdentityBinding.deriveDeviceId(key(1))
        val remembered = mutableListOf<String>()
        var persisted: String? = null
        assertEquals(id, LocalIdentityBinding.resolve(id, { "unused" }, { persisted = it }, { remembered += it }) { null })
        assertEquals(null, persisted)
        assertTrue(remembered.isEmpty())
    }

    @Test
    fun aLegacyIdNeverRemembersAnything() {
        val remembered = mutableListOf<String>()
        assertEquals("old-random-id", LocalIdentityBinding.resolve("old-random-id", { "unused" }, {}, { remembered += it }) { key(1) })
        assertTrue(remembered.isEmpty())
    }
}
