package com.fileapex.data.identity

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fileapex.data.db.seal.FieldSealer
import com.fileapex.data.seal.SealedText
import com.fileapex.platform.platformDeviceName
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalIdentityNameSealingTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private var sealer: FieldSealer? = null

    @Before
    fun setUp() {
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        SealedText.sealerProvider = { sealer }
        initAndroidLocalIdentity(context)
    }

    @After
    fun tearDown() {
        SealedText.sealerProvider = { null }
    }

    private val prefs get() = context.getSharedPreferences("fileapex_identity", Context.MODE_PRIVATE)

    @Test
    fun theNameIsStoredSealedAndReadsBack() {
        updateLocalDeviceName("  Kitchen Tablet ")
        assertFalse(prefs.getString("device_name", "")!!.contains("Kitchen"))
        assertEquals("Kitchen Tablet", loadLocalIdentity().deviceName)
    }

    @Test
    fun aPlainNameFromAnOlderVersionIsKeptAndSealed() {
        prefs.edit().putString("device_name", "Old Phone").commit()
        assertEquals("Old Phone", loadLocalIdentity().deviceName)
        assertFalse(prefs.getString("device_name", "")!!.contains("Old Phone"))
        assertEquals("Old Phone", loadLocalIdentity().deviceName)
    }

    @Test
    fun theDeviceIdStaysPlainAndStableWhateverTheKeyDoes() {
        val id = loadLocalIdentity().deviceId
        assertEquals(id, prefs.getString("device_id", null))
        sealer = null
        assertEquals(id, loadLocalIdentity().deviceId)
        assertEquals(id, prefs.getString("device_id", null))
    }

    @Test
    fun aLockedKeyKeepsTheStoredNameAndRenamingFailsLoudly() {
        updateLocalDeviceName("Kitchen Tablet")
        val stored = prefs.getString("device_name", "")
        sealer = null
        assertThrows(IllegalStateException::class.java) { updateLocalDeviceName("Other") }
        assertEquals(stored, prefs.getString("device_name", ""))
        assertEquals(platformDeviceName(), loadLocalIdentity().deviceName)
        assertTrue(stored!!.isNotEmpty())
    }

    @Test
    fun aSealedNameThatCannotBeOpenedIsReportedUnreadableNotAsTheFactoryName() {
        updateLocalDeviceName("Kitchen Tablet")
        sealer = null
        val locked = loadLocalIdentity()
        assertEquals(platformDeviceName(), locked.deviceName)
        assertFalse(locked.deviceNameReadable)
        sealer = FieldSealer(ByteArray(32) { it.toByte() })
        val open = loadLocalIdentity()
        assertEquals("Kitchen Tablet", open.deviceName)
        assertTrue(open.deviceNameReadable)
    }

    @Test
    fun aDeviceThatWasNeverRenamedHasAReadableFactoryName() {
        val identity = loadLocalIdentity()
        assertEquals(platformDeviceName(), identity.deviceName)
        assertTrue(identity.deviceNameReadable)
    }
}
