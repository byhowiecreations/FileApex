package com.fileapex.ui

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.SettingsKvStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingAndPopupGateTest {

    private class MemoryKvStore : SettingsKvStore {
        private val data = mutableMapOf<String, Any>()
        override fun contains(key: String): Boolean = key in data
        override fun getBoolean(key: String, default: Boolean): Boolean = (data[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { data[key] = value }
        override fun getString(key: String, default: String): String = (data[key] as? String) ?: default
        override fun putString(key: String, value: String) { data[key] = value }
        override fun getInt(key: String, default: Int): Int = (data[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { data[key] = value }
        override fun getLong(key: String, default: Long): Long = (data[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { data[key] = value }
        override fun keys(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { data.remove(key) }
        override fun clear() { data.clear() }
    }

    @Test
    fun `welcomeTutorialShown defaults to false and survives resetToDefaults once seen`() {
        val store = MemoryKvStore()
        val settings = BaseAppSettings(store)
        assertFalse(settings.welcomeTutorialShown.value)

        settings.setWelcomeTutorialShown(true)
        assertTrue(settings.welcomeTutorialShown.value)

        settings.resetToDefaults()
        assertTrue("Welcome tutorial dismissal must survive Leave Cluster wipe", settings.welcomeTutorialShown.value)

        val reloaded = BaseAppSettings(store, existingInstall = true)
        assertTrue("Welcome tutorial dismissal must remain persisted in store", reloaded.welcomeTutorialShown.value)
    }

    @Test
    fun `fresh install that has not seen tutorial keeps false across reset`() {
        val store = MemoryKvStore()
        val settings = BaseAppSettings(store)
        assertFalse(settings.welcomeTutorialShown.value)
        settings.resetToDefaults()
        assertFalse(settings.welcomeTutorialShown.value)
    }

    @Test
    fun `update and critical notice dialogs never auto-show at startup`() {
        assertFalse(StartupPromptGate.showStartupDialogs)
        assertFalse(
            StartupPromptGate.canShowUpdatePrompt(
                welcomeTutorialShown = false,
                managedByStore = false,
                answered = false,
                enabled = false,
                wiping = false,
                wipeOutcomePending = false
            )
        )
        assertFalse(
            StartupPromptGate.canShowCriticalNoticeConsent(
                welcomeTutorialShown = false,
                choiceMade = false,
                managedByStore = false,
                updatePromptShown = true,
                checkingForUpdates = true,
                wiping = false,
                wipeOutcomePending = false
            )
        )
        assertFalse(
            StartupPromptGate.canShowUpdatePrompt(
                welcomeTutorialShown = true,
                managedByStore = false,
                answered = false,
                enabled = false,
                wiping = false,
                wipeOutcomePending = false
            )
        )
        assertFalse(
            StartupPromptGate.canShowCriticalNoticeConsent(
                welcomeTutorialShown = true,
                choiceMade = false,
                managedByStore = false,
                updatePromptShown = true,
                checkingForUpdates = true,
                wiping = false,
                wipeOutcomePending = false
            )
        )
    }

    @Test
    fun `post-pair setup tracks pending update and critical notice choices until answered`() {
        assertTrue(StartupPromptGate.isUpdatePromptPending(managedByStore = false, answered = false, enabled = false))
        assertFalse(StartupPromptGate.isUpdatePromptPending(managedByStore = true, answered = false, enabled = false))
        assertFalse(StartupPromptGate.isUpdatePromptPending(managedByStore = false, answered = true, enabled = false))
        assertFalse(StartupPromptGate.isUpdatePromptPending(managedByStore = false, answered = false, enabled = true))

        assertTrue(StartupPromptGate.isCriticalNoticePending(choiceMade = false))
        assertFalse(StartupPromptGate.isCriticalNoticePending(choiceMade = true))
    }

    @Test
    fun `postPairSetupBannerDismissed persists across screen reloads and clears on resetToDefaults`() {
        val store = MemoryKvStore()
        val settings = BaseAppSettings(store)
        assertFalse(settings.postPairSetupBannerDismissed.value)

        settings.setPostPairSetupBannerDismissed(true)
        assertTrue(settings.postPairSetupBannerDismissed.value)

        val reloaded = BaseAppSettings(store, existingInstall = true)
        assertTrue(reloaded.postPairSetupBannerDismissed.value)

        reloaded.resetToDefaults()
        assertFalse(reloaded.postPairSetupBannerDismissed.value)
    }
}
