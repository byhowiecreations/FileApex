package com.fileapex.domain.presence

/** Android: ConnectivityManager NetworkCallback. */
actual fun registerLanNetworkTransitionListener() {
    LanNetworkTransitionMonitor.ensureRegistered()
}
