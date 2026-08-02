package com.fileapex.navigation

import com.fileapex.domain.share.IncomingSharePayload
import com.fileapex.presentation.BrowseTarget

sealed interface AppRoute {
    data object Devices : AppRoute
    data object Settings : AppRoute
    data object GenerateQr : AppRoute
    data object ScanQr : AppRoute
    data class Explorer(val target: BrowseTarget) : AppRoute
    data class ShareSend(
        val payload: IncomingSharePayload,
        val directTargetDeviceId: String? = null
    ) : AppRoute
}
