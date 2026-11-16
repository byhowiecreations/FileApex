package com.fileapex.ui.explorer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorerFavoritesPolicyTest {
    @Test
    fun favoritesAreAvailableForTheLocalExplorerOnEveryPlatform() {
        assertTrue(ExplorerFavoritesPolicy.isAvailable(isLocal = true, isPicker = false))
    }

    @Test
    fun aRemoteDeviceHasNoFavoritesBecauseTheStarIsOnlyForFilesOnThisDevice() {
        assertFalse(ExplorerFavoritesPolicy.isAvailable(isLocal = false, isPicker = false))
    }

    @Test
    fun aFilePickerShowsNoFavoritesButton() {
        assertFalse(ExplorerFavoritesPolicy.isAvailable(isLocal = true, isPicker = true))
    }
}
