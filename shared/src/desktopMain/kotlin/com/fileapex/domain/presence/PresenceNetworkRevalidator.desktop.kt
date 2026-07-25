package com.fileapex.domain.presence

/** Desktop: lightweight interface-address watcher. */
actual fun registerLanNetworkTransitionListener() {
    DesktopLanNetworkTransitionMonitor.ensureRegistered()
}
