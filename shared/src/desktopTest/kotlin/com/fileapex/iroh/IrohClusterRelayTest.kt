package com.fileapex.iroh

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IrohClusterRelayTest {
    private val settings = BaseAppSettings(MapKvStore())
    private val now = 10_000_000L

    @Test
    fun nothingIsSentUntilTheRelayWasSetOnSomeDevice() {
        assertNull(IrohClusterRelay.own(settings))
    }

    @Test
    fun theOwnChoiceIsSentOnceItHasAChangeTime() {
        settings.applyClusterIrohRelay(IrohRelaySetting(true, "https://relay.example.com", "t", now - 5))
        assertEquals(now - 5, IrohClusterRelay.own(settings)!!.changedAtMs)
    }

    @Test
    fun aNewerChoiceFromAPairedDeviceIsStoredAndAnOlderOneIsNot() {
        settings.applyClusterIrohRelay(IrohRelaySetting(true, "https://relay.example.com", "t", now - 100))
        assertEquals(false, IrohClusterRelay.adopt(settings, IrohRelaySetting(true, "https://old.example.com", "t", now - 200), now))
        assertEquals("https://relay.example.com", settings.irohRelayUrl.value)
        assertEquals(true, IrohClusterRelay.adopt(settings, IrohRelaySetting(true, "https://new.example.com", "u", now - 50), now))
        assertEquals("https://new.example.com", settings.irohRelayUrl.value)
        assertEquals("u", settings.irohRelayToken())
        assertEquals(now - 50, settings.irohRelaySetting().changedAtMs)
    }

    @Test
    fun anInvalidChoiceIsIgnoredWhateverItsTime() {
        assertEquals(false, IrohClusterRelay.adopt(settings, IrohRelaySetting(true, "http://relay.example.com", "t", now - 1), now))
        assertEquals(false, settings.irohRelayEnabled.value)
    }

    @Test
    fun nothingReceivedChangesNothing() {
        assertEquals(false, IrohClusterRelay.adopt(settings, null, now))
    }
}
