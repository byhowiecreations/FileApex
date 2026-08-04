package com.fileapex.platform

import android.content.Context
import com.fileapex.util.NetworkUtils

private var appContext: Context? = null

internal fun androidApplicationContextOrNull(): Context? = appContext

fun initAndroidLanConnectivity(context: Context) {
    appContext = context.applicationContext
}

actual fun isActiveLanConnectivity(): Boolean =
    NetworkUtils.lanBindCandidates().isNotEmpty()
