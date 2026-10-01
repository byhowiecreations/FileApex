package com.fileapex.domain.reset

expect object ClusterResetPlatform {
    fun eraseKeystoreAndSecureStorage()
    fun stateResetAndRestart()
}
