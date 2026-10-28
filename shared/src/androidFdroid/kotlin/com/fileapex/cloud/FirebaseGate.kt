package com.fileapex.cloud

import android.content.Context

/** The F-Droid build has no Firebase; these exist so shared Android code can call the same gate. */
object FirebaseGate {
    fun initIfLinkedAtLaunch(context: Context) = Unit

    fun onLinkChanged(context: Context, linked: Boolean) = Unit
}
