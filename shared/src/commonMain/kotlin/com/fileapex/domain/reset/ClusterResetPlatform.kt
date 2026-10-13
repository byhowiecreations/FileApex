package com.fileapex.domain.reset

expect object ClusterResetPlatform {
    /** True when the reset finishes inside the running app; false when the platform restarts the process. */
    val keepsAppRunning: Boolean
    fun eraseKeystoreAndSecureStorage()
    fun stateResetAndRestart()
}
