package com.fileapex.platform

import android.content.Context

/**
 * SSOT for [androidx.core.content.FileProvider] authority used by the manifest and URI builders.
 *
 * Must stay in sync with `android:authorities="${applicationId}.fileprovider"` in the app manifest.
 */
object FileApexFileProvider {
    const val AUTHORITY_SUFFIX = ".fileprovider"

    fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"
}
