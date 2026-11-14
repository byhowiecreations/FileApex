package com.fileapex.ui

/**
 * Prevents background dialogs (auto-update opt-in and critical notice consent) from stacking on top
 * of the first-run welcome tutorial or while a Leave Cluster wipe is settling.
 */
object StartupPromptGate {
    const val showStartupDialogs: Boolean = false

    fun isUpdatePromptPending(
        managedByStore: Boolean,
        answered: Boolean,
        enabled: Boolean
    ): Boolean = !managedByStore && !answered && !enabled

    fun isCriticalNoticePending(choiceMade: Boolean): Boolean = !choiceMade

    fun canShowUpdatePrompt(
        welcomeTutorialShown: Boolean,
        managedByStore: Boolean,
        answered: Boolean,
        enabled: Boolean,
        wiping: Boolean,
        wipeOutcomePending: Boolean
    ): Boolean =
        showStartupDialogs &&
            welcomeTutorialShown &&
            !wiping &&
            !wipeOutcomePending &&
            isUpdatePromptPending(managedByStore = managedByStore, answered = answered, enabled = enabled)

    fun canShowCriticalNoticeConsent(
        welcomeTutorialShown: Boolean,
        choiceMade: Boolean,
        managedByStore: Boolean,
        updatePromptShown: Boolean,
        checkingForUpdates: Boolean,
        wiping: Boolean,
        wipeOutcomePending: Boolean
    ): Boolean =
        showStartupDialogs &&
            welcomeTutorialShown &&
            !wiping &&
            !wipeOutcomePending &&
            isCriticalNoticePending(choiceMade = choiceMade) &&
            (managedByStore || updatePromptShown || checkingForUpdates)
}
