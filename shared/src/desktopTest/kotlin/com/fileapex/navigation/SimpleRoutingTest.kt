package com.fileapex.navigation

import com.fileapex.ui.adaptive.SimpleDestination
import org.junit.Assert.assertEquals
import org.junit.Test

/** Switching theme on the Themes page must show Settings on the first frame of the new theme, in either direction. */
class SimpleRoutingTest {
    @Test
    fun simpleShowingSettingsMakesTheRouteSettingsSoAnotherThemeOpensThere() {
        assertEquals(AppRoute.Settings, SimpleRouting.routeFor(SimpleDestination.Settings, AppRoute.Devices))
    }

    @Test
    fun leavingSettingsInSimpleLeavesTheSettingsRoute() {
        assertEquals(AppRoute.Devices, SimpleRouting.routeFor(SimpleDestination.Home, AppRoute.Settings))
    }

    @Test
    fun otherDestinationsLeaveTheRouteAlone() {
        assertEquals(AppRoute.Devices, SimpleRouting.routeFor(SimpleDestination.LocalFiles, AppRoute.Devices))
        assertEquals(AppRoute.Notes, SimpleRouting.routeFor(SimpleDestination.Browse, AppRoute.Notes))
    }

    @Test
    fun aSettingsRouteMakesSimpleShowSettingsWithoutWaitingForAnEffect() {
        assertEquals(SimpleDestination.Settings, SimpleRouting.destinationFor(AppRoute.Settings, SimpleDestination.Home))
    }

    @Test
    fun anotherRouteLeavesSimplesOwnDestination() {
        assertEquals(SimpleDestination.Browse, SimpleRouting.destinationFor(AppRoute.Devices, SimpleDestination.Browse))
    }
}
