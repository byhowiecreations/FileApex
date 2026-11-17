package com.fileapex.data.settings

import com.fileapex.notice.MapKvStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerFavoritesSettingTest {
    private val kv = MapKvStore()

    @Test
    fun togglingAddsThenRemovesAndKeepsTheOrderOfAddition() {
        val s = BaseAppSettings(kv)
        s.toggleExplorerFavorite("/a")
        s.toggleExplorerFavorite("/b")
        s.toggleExplorerFavorite("/c")
        s.toggleExplorerFavorite("/b")
        assertEquals(listOf("/a", "/c"), s.explorerFavorites.value)
    }

    @Test
    fun favoritesSurviveARestartAndResetToDefaultsClearsThem() {
        val s = BaseAppSettings(kv)
        s.toggleExplorerFavorite("/a")
        assertEquals(listOf("/a"), BaseAppSettings(kv).explorerFavorites.value)
        s.resetToDefaults()
        assertTrue(s.explorerFavorites.value.isEmpty())
        assertTrue(BaseAppSettings(kv).explorerFavorites.value.isEmpty())
    }
}
