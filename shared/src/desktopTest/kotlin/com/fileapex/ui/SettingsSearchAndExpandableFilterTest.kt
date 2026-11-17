package com.fileapex.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.KineticStyle
import com.fileapex.presentation.ExplorerSortMode
import com.fileapex.presentation.SettingsUiState
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.explorer.ExplorerFilterBar
import com.fileapex.ui.explorer.ExplorerFilterBarPolicy
import com.fileapex.ui.settings.SettingsSearchEngine
import com.fileapex.ui.settings.SettingsSearchIds
import com.fileapex.ui.settings.SettingsSearchOptions
import com.fileapex.ui.theme.FileApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchAndExpandableFilterTest {

    private fun allOptions(
        showAdvanced: Boolean = true,
        backupSync: Boolean = true,
        windowsFluent: Boolean = true
    ) = SettingsSearchOptions(
        playStoreBuild = false,
        desktopFileSelection = true,
        deviceNamePage = true,
        windowsFluent = windowsFluent,
        backupSync = backupSync,
        googleCloud = true,
        tailscale = true,
        iroh = true,
        groups = true,
        shareLink = true,
        autoLaunchOnDesktop = true,
        fullDiskAccess = true,
        showAdvancedSettings = showAdvanced,
        inUseAdvancedIds = emptySet(),
        navPosition = true
    )

    @Test
    fun `natural language queries rank relevant settings pages and provide match hints`() {
        val state = SettingsUiState()
        val options = allOptions()

        val hideIp = SettingsSearchEngine.search("how do i hide my ip", state, options)
        assertTrue(hideIp.isNotEmpty())
        assertTrue(
            hideIp.take(4).any {
                it.id in setOf(
                    SettingsMenuIds.DRIVE_RELAY,
                    SettingsMenuIds.TAILSCALE,
                    SettingsMenuIds.IROH,
                    SettingsMenuIds.SHARE_LINK
                )
            }
        )

        val notifications = SettingsSearchEngine.search("turn off critical alerts", state, options)
        assertEquals(SettingsMenuIds.NOTIFICATIONS, notifications.firstOrNull()?.id)
        assertNotNull(notifications.firstOrNull()?.matchedHint)

        val screenshots = SettingsSearchEngine.search("auto save screenshots to computer", state, options)
        assertTrue(
            screenshots.take(2).any { it.id == SettingsMenuIds.BACKUP_SYNC || it.id == SettingsMenuIds.CLIPBOARD }
        )

        val darkMode = SettingsSearchEngine.search("switch to dark mode kinetic theme", state, options)
        assertEquals(SettingsMenuIds.THEMES, darkMode.firstOrNull()?.id)

        val startup = SettingsSearchEngine.search("open app when windows boots", state, options)
        assertEquals(SettingsMenuIds.AUTO_LAUNCH, startup.firstOrNull()?.id)

        val pinTimeout = SettingsSearchEngine.search("change lock passcode idle timeout", state, options)
        assertEquals(SettingsMenuIds.PIN_REQUIRED, pinTimeout.firstOrNull()?.id)

        val versionAbout = SettingsSearchEngine.search("what version and open source licenses", state, options)
        assertTrue(versionAbout.take(2).any { it.id == SettingsSearchIds.ABOUT })
    }

    @Test
    fun `search tolerates typos surfaces advanced settings when hidden and respects availability`() {
        val state = SettingsUiState()

        val typo = SettingsSearchEngine.search("notifcation", state, allOptions())
        assertEquals(SettingsMenuIds.NOTIFICATIONS, typo.firstOrNull()?.id)

        val hiddenAdvanced = SettingsSearchEngine.search(
            "erase all paired devices and wipe cluster",
            state,
            allOptions(showAdvanced = false)
        )
        assertEquals(SettingsMenuIds.LEAVE_CLUSTER, hiddenAdvanced.firstOrNull()?.id)
        assertTrue(hiddenAdvanced.first().isAdvanced)

        val noBackupOrWindows = allOptions(backupSync = false, windowsFluent = false)
        assertFalse(SettingsSearchEngine.search("photo backup", state, noBackupOrWindows).any { it.id == SettingsMenuIds.BACKUP_SYNC })
        assertFalse(SettingsSearchEngine.search("mica backdrop", state, noBackupOrWindows).any { it.id == SettingsMenuIds.WINDOWS_DESIGN })

        assertTrue(SettingsSearchEngine.search("   ", state, allOptions()).isEmpty())
        assertTrue(SettingsSearchEngine.search("zzzzqqqqxxxx", state, allOptions()).isEmpty())
    }

    @Test
    fun `Kinetic, Clean and Flux explorer filter bars use the expandable search icon`() {
        assertTrue(ExplorerFilterBarPolicy.usesExpandableSearch(AppTheme.KINETIC_SPHERE, hasLeading = false))
        assertTrue(ExplorerFilterBarPolicy.usesExpandableSearch(AppTheme.SIMPLE, hasLeading = true))
        assertTrue(ExplorerFilterBarPolicy.usesExpandableSearch(AppTheme.CLEAN, hasLeading = false))
        assertTrue(ExplorerFilterBarPolicy.usesExpandableSearch(AppTheme.FLUX_GLASS, hasLeading = false))
        assertFalse(ExplorerFilterBarPolicy.usesExpandableSearch(AppTheme.FREESTYLE, hasLeading = false))
    }

    @Test
    fun `Settings header search pill shares the Settings row and expands smoothly on click`() {
        var pillBounds: androidx.compose.ui.geometry.Rect? = null
        var query = ""
        val scene = ImageComposeScene(width = 480, height = 140, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.SIMPLE) {
                FileApexPaneSectionHeader(
                    title = "Settings",
                    searchQuery = query,
                    onSearchQueryChange = { query = it },
                    searchPlaceholder = "Search settings",
                    searchButtonModifier = Modifier.onGloballyPositioned { pillBounds = it.boundsInRoot() }
                )
            }
        }
        try {
            scene.render(0L).close()
            val collapsed = pillBounds ?: error("Missing settings search pill bounds")
            assertTrue("Expected compact search icon pill <= 56dp wide, got ${collapsed.width}", collapsed.width <= 56f)

            val center = androidx.compose.ui.geometry.Offset(
                collapsed.left + collapsed.width / 2f,
                collapsed.top + collapsed.height / 2f
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = center,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
                timeMillis = 10L
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = center,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = false),
                timeMillis = 20L
            )
            for (ms in 1..15) {
                scene.render(ms * 30_000_000L).close()
            }
            val expanded = pillBounds ?: error("Missing expanded settings search bounds")
            assertTrue("Expected search pill to expand across Settings row (>240dp), got ${expanded.width}", expanded.width >= 240f)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `Kinetic explorer filter bar starts collapsed as search icon and expands when clicked`() {
        var searchBounds: androidx.compose.ui.geometry.Rect? = null
        var query = ""
        val scene = ImageComposeScene(width = 480, height = 120, density = Density(1f)) {
            FileApexTheme(appTheme = AppTheme.KINETIC_SPHERE, kineticStyle = KineticStyle.JADED_STEEL) {
                ExplorerFilterBar(
                    query = query,
                    onQueryChange = { query = it },
                    sortMode = ExplorerSortMode.Name,
                    onSortModeChange = {},
                    searchPillModifier = Modifier.onGloballyPositioned { searchBounds = it.boundsInRoot() }
                )
            }
        }
        try {
            scene.render(0L).close()
            val collapsed = searchBounds ?: error("Missing Kinetic filter pill bounds")
            assertTrue("Expected Kinetic filter to start collapsed as icon (<=56dp), got ${collapsed.width}", collapsed.width <= 56f)

            val center = androidx.compose.ui.geometry.Offset(
                collapsed.left + collapsed.width / 2f,
                collapsed.top + collapsed.height / 2f
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = center,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
                timeMillis = 10L
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = center,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = false),
                timeMillis = 20L
            )
            for (ms in 1..15) {
                scene.render(ms * 30_000_000L).close()
            }
            val expanded = searchBounds ?: error("Missing expanded Kinetic filter bounds")
            assertTrue("Expected Kinetic filter pill to expand on click (>200dp), got ${expanded.width}", expanded.width >= 200f)
        } finally {
            scene.close()
        }
    }
}
