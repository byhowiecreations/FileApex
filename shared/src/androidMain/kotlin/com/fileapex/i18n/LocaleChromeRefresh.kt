package com.fileapex.i18n

/**
 * ComposeApp registers FGS notification refresh here so shared code can rebuild
 * the persistent server alert after [AppI18n.setLocale] without importing composeApp.
 */
object LocaleChromeRefresh {
    @Volatile
    var listener: (() -> Unit)? = null

    fun fire() {
        listener?.invoke()
    }
}
