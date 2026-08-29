package com.fileapex.platform

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacOsExtensionRegistrationPolicyTest {

    @Test
    fun skipsOnlyWhenStampMatchesAndBothExtensionsAreListed() {
        assertTrue(
            MacOsExtensionRegistrationPolicy.shouldSkipPluginkit(
                stampUnchanged = true,
                shareListed = true,
                bulletinListed = true
            )
        )
    }

    @Test
    fun reregistersAfterOsDropsPluginsEvenIfStampMatches() {
        assertFalse(
            MacOsExtensionRegistrationPolicy.shouldSkipPluginkit(
                stampUnchanged = true,
                shareListed = false,
                bulletinListed = true
            )
        )
        assertFalse(
            MacOsExtensionRegistrationPolicy.shouldSkipPluginkit(
                stampUnchanged = true,
                shareListed = true,
                bulletinListed = false
            )
        )
        assertFalse(
            MacOsExtensionRegistrationPolicy.shouldSkipPluginkit(
                stampUnchanged = true,
                shareListed = false,
                bulletinListed = false
            )
        )
    }

    @Test
    fun reregistersWhenStampChanges() {
        assertFalse(
            MacOsExtensionRegistrationPolicy.shouldSkipPluginkit(
                stampUnchanged = false,
                shareListed = true,
                bulletinListed = true
            )
        )
    }
}
