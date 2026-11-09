package com.fileapex.ui.explorer

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.fileapex.presentation.DevicesUiState
import com.fileapex.ui.DevicesSnackbars
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessingDeviceSnackbarTest {

    @Test
    fun onlyARemoteDeviceWhoseFirstListingHasNotArrivedNeedsTheSnackbar() {
        assertTrue(shouldShowAccessingSnackbar(isRemote = true, firstListingSettled = false))
        assertFalse(shouldShowAccessingSnackbar(isRemote = true, firstListingSettled = true))
        assertFalse(shouldShowAccessingSnackbar(isRemote = false, firstListingSettled = false))
        assertFalse(shouldShowAccessingSnackbar(isRemote = false, firstListingSettled = true))
    }

    private class Frames {
        private var nanos = 0L
        fun run(scene: ImageComposeScene, milliseconds: Long) {
            val end = System.currentTimeMillis() + milliseconds
            while (System.currentTimeMillis() < end) {
                scene.render(nanos)
                nanos += 16_000_000L
                Thread.sleep(16)
            }
        }
    }

    private class ExplorerHarness(remote: Boolean, settled: Boolean) {
        val host = SnackbarHostState()
        val settled = mutableStateOf(settled)
        val scene = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            AccessingDeviceSnackbar(host = host, isRemote = remote, firstListingSettled = this@ExplorerHarness.settled.value, deviceName = "Ursula-Ubuntu")
        }
        private val frames = Frames()
        fun waitMs(ms: Long) = frames.run(scene, ms)
        val message: String? get() = host.currentSnackbarData?.visuals?.message
    }

    @Test
    fun aRemoteExplorerShowsAccessingAtOnceWithTheDeviceName() {
        val h = ExplorerHarness(remote = true, settled = false)
        try {
            h.waitMs(300)
            assertNotNull("no snackbar while the first listing is pending", h.message)
            assertTrue(h.message!!, h.message!!.contains("Ursula-Ubuntu"))
        } finally { h.scene.close() }
    }

    @Test
    fun theSnackbarGoesAwayWhenTheFirstListingArrives() {
        val h = ExplorerHarness(remote = true, settled = false)
        try {
            h.waitMs(300)
            assertNotNull(h.message)
            h.settled.value = true
            h.waitMs(600)
            assertNull("the snackbar stayed after the listing arrived", h.message)
        } finally { h.scene.close() }
    }

    @Test
    fun anExplorerThatAlreadyHasItsListingNeverShowsIt() {
        val h = ExplorerHarness(remote = true, settled = true)
        try {
            h.waitMs(400)
            assertNull(h.message)
        } finally { h.scene.close() }
    }

    @Test
    fun aLocalFolderNeverShowsIt() {
        val h = ExplorerHarness(remote = false, settled = false)
        try {
            h.waitMs(400)
            assertNull(h.message)
        } finally { h.scene.close() }
    }

    private class DevicesHarness {
        val host = SnackbarHostState()
        val state = mutableStateOf(DevicesUiState())
        val scene = ImageComposeScene(width = 300, height = 200, density = Density(1f)) {
            DevicesSnackbars(state = state.value, host = host, progressShownElsewhere = false, onShown = {})
        }
        private val frames = Frames()
        fun waitMs(ms: Long) = frames.run(scene, ms)
        val message: String? get() = host.currentSnackbarData?.visuals?.message
    }

    @Test
    fun theDevicesScreenShowsAccessingWhileTheConnectionIsBeingMade() {
        val h = DevicesHarness()
        try {
            h.waitMs(100)
            assertNull(h.message)
            h.state.value = DevicesUiState(connectingDeviceId = "d1", connectingDeviceName = "Samsung Fold8")
            h.waitMs(300)
            assertNotNull(h.message)
            assertTrue(h.message!!, h.message!!.contains("Samsung Fold8"))
            h.state.value = DevicesUiState()
            h.waitMs(600)
            assertNull(h.message)
        } finally { h.scene.close() }
    }

    @Test
    fun theTextExistsInAllThreeLanguages() {
        val dir = java.io.File(System.getProperty("user.dir"), "src/desktopMain/resources/i18n")
        for (language in listOf("en", "es", "zh-rCN")) {
            assertTrue("$language is missing accessing_device", java.io.File(dir, "$language.xml").readText().contains("name=\"accessing_device\""))
        }
    }
}
