package com.fileapex.navigation

import com.fileapex.ui.adaptive.SimpleDestination

/** Settings is the one place every theme shares, so the route says so while Simple shows it. */
object SimpleRouting {
    fun routeFor(destination: SimpleDestination, route: AppRoute): AppRoute = when {
        destination == SimpleDestination.Settings -> AppRoute.Settings
        route == AppRoute.Settings -> AppRoute.Devices
        else -> route
    }

    fun destinationFor(route: AppRoute, simpleDestination: SimpleDestination): SimpleDestination =
        if (route == AppRoute.Settings) SimpleDestination.Settings else simpleDestination
}
