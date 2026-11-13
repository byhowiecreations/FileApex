package com.fileapex.ui

import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.presentation.BrowseTarget
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.adaptive.simple.SimpleHomeSplitState
import com.fileapex.ui.adaptive.simple.SimpleSplitViewPolicy
import com.fileapex.ui.explorer.ExplorerFilterLabelPolicy
import com.fileapex.ui.explorer.ExplorerSplitPolicy
import com.fileapex.ui.settings.ThemePreviewCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitViewAndSettingsPreviewTest {

    @Test
    fun `Split View is exposed cleanly for both LocalFiles and Browse destinations`() {
        assertTrue(SimpleSplitViewPolicy.isSplitSupported(SimpleDestination.LocalFiles))
        assertTrue(SimpleSplitViewPolicy.isSplitSupported(SimpleDestination.Browse))
        assertFalse(SimpleSplitViewPolicy.isSplitSupported(SimpleDestination.Home))
        assertFalse(SimpleSplitViewPolicy.isSplitSupported(SimpleDestination.Settings))
    }

    @Test
    fun `ThemePreviewCatalog covers every AppTheme without omissions`() {
        val specs = ThemePreviewCatalog.allSpecs()
        assertEquals(AppTheme.entries.toSet(), specs.map { it.theme }.toSet())
        assertTrue(specs.all { it.backgroundHex != 0L && it.accentHex != 0L && it.drawableName.startsWith("theme_thumb_") })
        assertEquals(AppTheme.entries.size, specs.map { it.drawableName }.toSet().size)
    }

    @Test
    fun `DesktopNavPosition exposes all three options for settings sub-page`() {
        assertEquals(
            listOf(DesktopNavPosition.Left, DesktopNavPosition.Bottom, DesktopNavPosition.Right),
            DesktopNavPosition.entries.toList()
        )
    }

    @Test
    fun `ExplorerSplitPolicy enables split mode for both Local and Remote targets`() {
        val local = BrowseTarget.Local("local", "This Mac", "/tmp")
        val remote = BrowseTarget.Remote("peer-1", "Samsung Fold8", "127.0.0.1", 8080, "/")
        assertTrue(ExplorerSplitPolicy.isSplitOpen(hostSplit = true, viewMode = ExplorerViewMode.Split, target = local))
        assertTrue(ExplorerSplitPolicy.isSplitOpen(hostSplit = true, viewMode = ExplorerViewMode.Split, target = remote))
        assertFalse(ExplorerSplitPolicy.isSplitOpen(hostSplit = true, viewMode = ExplorerViewMode.List, target = remote))
        assertFalse(ExplorerSplitPolicy.isSplitOpen(hostSplit = false, viewMode = ExplorerViewMode.Split, target = remote))
    }

    @Test
    fun `Overview Browse Split View routes directly to Browse destination`() {
        assertEquals(SimpleDestination.Browse, SimpleSplitViewPolicy.overviewSplitDestination())
    }

    @Test
    fun `ExplorerFilterLabelPolicy uses concise single-word Filter placeholder key`() {
        assertEquals("filter", ExplorerFilterLabelPolicy.placeholderKey())
    }

    @Test
    fun `Home Browse Split View always enters Split even when rail mode was toggled off`() {
        val state = SimpleHomeSplitState()
        var persistedMode = ExplorerViewMode.Grid

        val dest = state.launchFromHome(persistedMode) { persistedMode = it }
        assertEquals(SimpleDestination.Browse, dest)
        assertEquals(ExplorerViewMode.Split, persistedMode)

        // Navigating to another destination via SimpleRail restores the user's non-split preference.
        state.onNavigateAway(persistedMode) { persistedMode = it }
        assertEquals(ExplorerViewMode.Grid, persistedMode)
    }

    @Test
    fun `Explicitly toggling Split off after Home launch updates remembered rail mode`() {
        val state = SimpleHomeSplitState()
        var persistedMode = ExplorerViewMode.Grid

        state.launchFromHome(persistedMode) { persistedMode = it }
        assertEquals(ExplorerViewMode.Split, persistedMode)

        persistedMode = ExplorerViewMode.List
        state.onViewModeChanged(persistedMode)

        state.onNavigateAway(persistedMode) { persistedMode = it }
        assertEquals(ExplorerViewMode.List, persistedMode)
    }

    @Test
    fun `Home Browse Split View preserves Split mode on navigation away when already enabled`() {
        val state = SimpleHomeSplitState()
        var persistedMode = ExplorerViewMode.Split

        state.launchFromHome(persistedMode) { persistedMode = it }
        state.onNavigateAway(persistedMode) { persistedMode = it }
        assertEquals(ExplorerViewMode.Split, persistedMode)
    }

    @Test
    fun `ThemeBadgeLayoutPolicy prevents DEFAULT badge from wrapping onto multiple lines`() {
        assertEquals(1, com.fileapex.ui.settings.ThemeBadgeLayoutPolicy.maxLines)
        assertFalse(com.fileapex.ui.settings.ThemeBadgeLayoutPolicy.softWrap)
    }
}
