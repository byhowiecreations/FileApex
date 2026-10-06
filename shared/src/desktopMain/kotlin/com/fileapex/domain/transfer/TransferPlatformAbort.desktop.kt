package com.fileapex.domain.transfer

import com.fileapex.platform.DesktopMacTrayBridge

actual fun abortInFlightPlatformTransfers() {
    DesktopMacTrayBridge.cancelLanTransfers()
}
