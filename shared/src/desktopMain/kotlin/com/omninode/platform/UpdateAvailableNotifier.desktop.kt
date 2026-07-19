package com.omninode.platform

actual fun notifyAppUpdateAvailable(versionLabel: String, detail: String?) {
    println(
        "UpdateAvailableNotifier: OmniNode $versionLabel available" +
            (detail?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
    )
}
