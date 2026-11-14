package com.fileapex.domain.pairing

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.ui.NearbyDiscoveredPeersBanner
import com.fileapex.ui.UnpairedLanDiscoveryPolicy
import com.fileapex.ui.theme.FileApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnpairedLanDiscoveryPolicyTest {

    private val samplePeer = PairingBeacon(
        deviceName = "Pixel 9",
        ipAddress = "10.0.0.42",
        port = 8443,
        timestamp = 1_000L,
        deviceId = "fx-peer-1",
        tlsPort = 8444
    )

    @Test
    fun `auto discovery is enabled only when unpaired, loaded, and not in demo mode`() {
        assertFalse(UnpairedLanDiscoveryPolicy.shouldAutoDiscover(pairedRowsCount = 0, isDemoActive = false, isInitialLoadComplete = false))
        assertFalse(UnpairedLanDiscoveryPolicy.shouldAutoDiscover(pairedRowsCount = 0, isDemoActive = true, isInitialLoadComplete = true))
        assertFalse(UnpairedLanDiscoveryPolicy.shouldAutoDiscover(pairedRowsCount = 1, isDemoActive = false, isInitialLoadComplete = true))
        assertFalse(UnpairedLanDiscoveryPolicy.shouldAutoDiscover(pairedRowsCount = 3, isDemoActive = false, isInitialLoadComplete = true))
        assertTrue(UnpairedLanDiscoveryPolicy.shouldAutoDiscover(pairedRowsCount = 0, isDemoActive = false, isInitialLoadComplete = true))
    }

    @Test
    fun `visibleDiscoveredPeers hides nearby peers as soon as auto discovery is inactive`() {
        assertEquals(emptyList<PairingBeacon>(), UnpairedLanDiscoveryPolicy.visibleDiscoveredPeers(false, listOf(samplePeer)))
        assertEquals(listOf(samplePeer), UnpairedLanDiscoveryPolicy.visibleDiscoveredPeers(true, listOf(samplePeer)))
    }

    @Test
    fun `nearby discovered peers banner renders discovered device cleanly`() {
        val scene = ImageComposeScene(width = 640, height = 160, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                NearbyDiscoveredPeersBanner(
                    peers = listOf(samplePeer),
                    isWorking = false,
                    onPairPeer = {}
                )
            }
        }
        try {
            scene.render(0L).close()
        } finally {
            scene.close()
        }
    }
}
