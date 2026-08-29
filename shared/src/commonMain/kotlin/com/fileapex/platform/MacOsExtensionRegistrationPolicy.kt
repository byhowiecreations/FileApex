package com.fileapex.platform

internal object MacOsExtensionRegistrationPolicy {
    fun shouldSkipPluginkit(
        stampUnchanged: Boolean,
        shareListed: Boolean,
        bulletinListed: Boolean
    ): Boolean = stampUnchanged && shareListed && bulletinListed
}
