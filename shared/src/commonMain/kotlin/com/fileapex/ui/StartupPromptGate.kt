package com.fileapex.ui

/**
 * Prevents background dialogs (auto-update opt-in and critical notice consent) from stacking on top
 * of the first-run welcome tutorial or while a Leave Cluster wipe is settling.
 */
object StartupPromptGate {
    fun canShowUpdatePrompt(
        welcomeTutorialShown: Boolean,
        managedByStore: Boolean,
        answered: Boolean,
        enabled: Boolean,
        wiping: Boolean,
        wipeOutcomePending: Boolean
    ): Boolean = welcomeTutorialShown && !managedByStore && !answered && !enabled && !wiping && !wipeOutcomePending

    fun canShowCriticalNoticeConsent(
        welcomeTutorialShown: Boolean,
        choiceMade: Boolean,
        managedByStore: Boolean,
        updatePromptShown: Boolean,
        checkingForUpdates: Boolean,
        wiping: Boolean,
        wipeOutcomePending: Boolean
    ): Boolean =
        welcomeTutorialShown &&
            !choiceMade &&
            !wiping &&
            !wipeOutcomePending &&
            (managedByStore || updatePromptShown || checkingForUpdates)
}
