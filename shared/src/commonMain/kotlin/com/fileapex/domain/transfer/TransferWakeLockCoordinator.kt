package com.fileapex.domain.transfer

internal expect object TransferWakeLockCoordinator {
    fun acquire()
    fun release()
    fun releaseAll()
}
