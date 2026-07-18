package com.omninode.navigation

import com.omninode.presentation.BrowseTarget

sealed interface AppRoute {
    data object Devices : AppRoute
    data object Settings : AppRoute
    data object GenerateQr : AppRoute
    data object ScanQr : AppRoute
    data class Explorer(val target: BrowseTarget) : AppRoute
}
