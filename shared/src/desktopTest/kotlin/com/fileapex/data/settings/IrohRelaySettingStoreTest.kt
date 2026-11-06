package com.fileapex.data.settings

import com.fileapex.iroh.IrohRelaySetting
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohRelaySettingStoreTest {
    private val store = MapKvStore()
    private val settings = BaseAppSettings(store)

    @Test
    fun nothingSavedMeansNoRelayAndNoChangeTime() {
        assertEquals(IrohRelaySetting(false, "", "", 0L), settings.irohRelaySetting())
    }

    @Test
    fun savingByHandStampsTheChangeWithTheCurrentTime() {
        val before = System.currentTimeMillis()
        assertTrue(settings.saveIrohRelay(true, "https://relay.example.com", "tok"))
        val saved = settings.irohRelaySetting()
        assertEquals(IrohRelaySetting(true, "https://relay.example.com", "tok", saved.changedAtMs), saved)
        assertTrue(saved.changedAtMs in before..System.currentTimeMillis())
    }

    @Test
    fun switchingTheRelayOffByHandIsAChangeToo() {
        settings.saveIrohRelay(true, "https://relay.example.com", "tok")
        val first = settings.irohRelaySetting().changedAtMs
        Thread.sleep(5)
        settings.saveIrohRelay(false, "https://relay.example.com", "tok")
        val off = settings.irohRelaySetting()
        assertFalse(off.enabled)
        assertTrue(off.changedAtMs > first)
        assertEquals("https://relay.example.com", off.url)
    }

    @Test
    fun aSettingFromTheClusterKeepsTheSendersTimestampAndTurnsTheRelayOn() {
        settings.applyClusterIrohRelay(IrohRelaySetting(true, "https://relay.example.com", "tok", 5_000L))
        assertTrue(settings.irohRelayEnabled.value)
        assertEquals("https://relay.example.com", settings.irohRelayUrl.value)
        assertEquals("tok", settings.irohRelayToken())
        assertEquals(5_000L, settings.irohRelaySetting().changedAtMs)
    }

    @Test
    fun aClusterOffKeepsTheSavedAddressAndTokenForNextTime() {
        settings.saveIrohRelay(true, "https://relay.example.com", "tok")
        settings.applyClusterIrohRelay(IrohRelaySetting(false, "", "", 9_000_000_000_000L))
        assertFalse(settings.irohRelayEnabled.value)
        assertEquals("https://relay.example.com", settings.irohRelayUrl.value)
        assertEquals("tok", settings.irohRelayToken())
        assertEquals(9_000_000_000_000L, settings.irohRelaySetting().changedAtMs)
    }

    @Test
    fun theTokenIsStillKeptWhereSecretsAreSealed() {
        assertTrue(BaseAppSettings.SECRET_KEYS.contains(BaseAppSettings.KEY_IROH_RELAY_TOKEN))
    }

    @Test
    fun resettingToDefaultsForgetsTheClusterSettingAndItsTimestamp() {
        settings.applyClusterIrohRelay(IrohRelaySetting(true, "https://relay.example.com", "tok", 5_000L))
        settings.resetToDefaults()
        assertEquals(IrohRelaySetting(false, "", "", 0L).enabled, settings.irohRelaySetting().enabled)
        assertEquals(0L, settings.irohRelaySetting().changedAtMs)
    }
}
