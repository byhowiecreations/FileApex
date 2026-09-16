package com.fileapex.domain.transfer

internal actual object TransferWakeLockCoordinator {
    actual fun acquire() = Unit
    actual fun release() = Unit
    actual fun releaseAll() = Unit
}
