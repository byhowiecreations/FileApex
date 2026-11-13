package com.fileapex.ui.adaptive.simple

import com.fileapex.data.settings.BaseAppSettings
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.data.settings.SettingsKvStore
import com.fileapex.domain.diagnostics.BatteryDiagnostics
import com.fileapex.domain.diagnostics.NetworkDiagnostics
import com.fileapex.domain.diagnostics.PeerDeviceDiagnostics
import com.fileapex.domain.diagnostics.StorageDiagnostics
import com.fileapex.domain.presence.RouteKind
import com.fileapex.platform.DesktopMacWindowChrome
import com.fileapex.presentation.DeviceListRow
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.adaptive.SimpleRailEntry
import javax.swing.JRootPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleNavPositionAndOverviewTest {

    private class MemoryKvStore : SettingsKvStore {
        private val data = mutableMapOf<String, Any>()
        override fun contains(key: String): Boolean = key in data
        override fun getBoolean(key: String, default: Boolean): Boolean = (data[key] as? Boolean) ?: default
        override fun putBoolean(key: String, value: Boolean) { data[key] = value }
        override fun getString(key: String, default: String): String = (data[key] as? String) ?: default
        override fun putString(key: String, value: String) { data[key] = value }
        override fun getInt(key: String, default: Int): Int = (data[key] as? Int) ?: default
        override fun putInt(key: String, value: Int) { data[key] = value }
        override fun getLong(key: String, default: Long): Long = (data[key] as? Long) ?: default
        override fun putLong(key: String, value: Long) { data[key] = value }
        override fun keys(prefix: String): List<String> = data.keys.filter { it.startsWith(prefix) }
        override fun remove(key: String) { data.remove(key) }
        override fun clear() { data.clear() }
    }

    @Test
    fun `DesktopNavPosition cycles Left to Bottom to Right to Left and persists`() {
        assertEquals(DesktopNavPosition.Left, DesktopNavPosition.DEFAULT)
        assertEquals(DesktopNavPosition.Bottom, DesktopNavPosition.Left.next())
        assertEquals(DesktopNavPosition.Right, DesktopNavPosition.Bottom.next())
        assertEquals(DesktopNavPosition.Left, DesktopNavPosition.Right.next())

        val store = MemoryKvStore()
        val settings = BaseAppSettings(store)
        assertEquals(DesktopNavPosition.Left, settings.desktopNavPosition.value)

        settings.setDesktopNavPosition(DesktopNavPosition.Bottom)
        assertEquals(DesktopNavPosition.Bottom, settings.desktopNavPosition.value)
        assertEquals(DesktopNavPosition.Bottom, BaseAppSettings(store, existingInstall = true).desktopNavPosition.value)

        settings.resetToDefaults()
        assertEquals(DesktopNavPosition.Left, settings.desktopNavPosition.value)
    }

    @Test
    fun `Android uses width based nav placement while desktop uses explicit position`() {
        assertEquals(
            DesktopNavPosition.Bottom,
            SimpleNavPlacementPolicy.resolve(isDesktop = false, windowIsWide = false, desktopPosition = DesktopNavPosition.Right)
        )
        assertEquals(
            DesktopNavPosition.Left,
            SimpleNavPlacementPolicy.resolve(isDesktop = false, windowIsWide = true, desktopPosition = DesktopNavPosition.Bottom)
        )
        assertEquals(
            DesktopNavPosition.Right,
            SimpleNavPlacementPolicy.resolve(isDesktop = true, windowIsWide = false, desktopPosition = DesktopNavPosition.Right)
        )
    }

    @Test
    fun `SimpleOverviewState transitions across Welcome, NotifSetup, and NotificationsActive`() {
        assertEquals(
            SimpleOverviewScreenMode.Welcome,
            SimpleOverviewStateResolver.resolve(hasAnyPairedDevice = false, hasConnectedDevice = false, notificationReady = false)
        )
        assertEquals(
            SimpleOverviewScreenMode.ConnectedNotifSetup,
            SimpleOverviewStateResolver.resolve(hasAnyPairedDevice = true, hasConnectedDevice = true, notificationReady = false)
        )
        assertEquals(
            SimpleOverviewScreenMode.ConnectedNotifActive,
            SimpleOverviewStateResolver.resolve(hasAnyPairedDevice = true, hasConnectedDevice = true, notificationReady = true)
        )
    }

    @Test
    fun `SimpleRailEntry includes BulletinBoard in primary rail entries`() {
        assertTrue(SimpleRailEntry.entries.contains(SimpleRailEntry.BulletinBoard))
    }

    @Test
    fun `SimpleRail omits Clipboard since Clipboard lives on Overview`() {
        val railEntries = SimpleRailCatalog.primaryEntries()
        assertFalse(SimpleRailEntry.Clipboard in railEntries)
        assertTrue(SimpleRailEntry.LocalFiles in railEntries)
        assertTrue(SimpleRailEntry.Browse in railEntries)
        assertTrue(SimpleRailEntry.BulletinBoard in railEntries)
        assertTrue(SimpleRailEntry.DeviceManagement in railEntries)
        assertTrue(SimpleRailEntry.Settings in railEntries)
    }

    @Test
    fun `Compact status pill reflects live route kind across LAN Wi-Fi, Tailscale, and Iroh`() {
        val row = DeviceListRow(
            deviceId = "peer-1",
            deviceName = "Samsung Fold8",
            online = true,
            appVersion = "0.20.12"
        )
        val diag = PeerDeviceDiagnostics(
            collectedAtEpochMs = 1L,
            battery = BatteryDiagnostics(levelPercent = 28),
            storage = StorageDiagnostics(totalBytes = 200L * 1024 * 1024 * 1024, usedBytes = 8L * 1024 * 1024 * 1024),
            network = NetworkDiagnostics(interfaceType = "Wi-Fi")
        )
        val lanPill = buildCompactStatusPill(row, diag, RouteKind.Lan, irohPath = null)
        assertTrue(lanPill.contains("Online"))
        assertTrue(lanPill.contains("Wi-Fi"))

        val tailscalePill = buildCompactStatusPill(row, diag, RouteKind.Tailnet, irohPath = null)
        assertTrue(tailscalePill.contains("Tailscale"))
        assertFalse(tailscalePill.contains("Wi-Fi"))

        val irohDirectPill = buildCompactStatusPill(row, diag, RouteKind.Iroh, irohPath = "direct")
        assertTrue(irohDirectPill.contains("Iroh"))
        assertFalse(irohDirectPill.contains("Wi-Fi"))

        val irohRelayPill = buildCompactStatusPill(row, diag, RouteKind.Iroh, irohPath = "relay")
        assertTrue(irohRelayPill.contains("Iroh Relay"))

        val offlinePill = buildCompactStatusPill(row.copy(online = false), diag, RouteKind.Lan, irohPath = null)
        assertTrue(offlinePill.startsWith("Offline"))
    }

    @Test
    fun `SimplePopoverPlacement clamps vertically inside short windows without clipping`() {
        val fitShortWindow = SimplePopoverPlacementPolicy.computeVerticalOffsetPx(
            anchorTopPx = 380f,
            windowHeightPx = 560f,
            contentHeightPx = 400f,
            marginPx = 8f
        )
        assertEquals(152f, fitShortWindow, 0.5f)
        assertTrue(fitShortWindow + 400f <= 560f - 8f)

        val maxCap = SimplePopoverPlacementPolicy.maxContentHeightPx(windowHeightPx = 420f, marginPx = 8f)
        assertEquals(404f, maxCap, 0.5f)
    }

    @Test
    fun `Overview header stays side by side until device title and status pill actually collide`() {
        assertTrue(
            SimpleHeaderLayoutPolicy.fitsSideBySide(
                availableWidthPx = 400f,
                leftWidthPx = 140f,
                rightWidthPx = 220f,
                minGapPx = 12f
            )
        )
        assertFalse(
            SimpleHeaderLayoutPolicy.fitsSideBySide(
                availableWidthPx = 360f,
                leftWidthPx = 140f,
                rightWidthPx = 220f,
                minGapPx = 12f
            )
        )
    }

    @Test
    fun `SimpleRail uses compact Devices label key so capsule label never clips`() {
        assertEquals("devices", SimpleRailCatalog.managementLabelKey())
    }

    @Test
    fun `DesktopMacWindowChrome enables full window content and top traffic-light inset on macOS`() {
        assertEquals(28, DesktopMacWindowChrome.topContentInsetDp(isMacOs = true))
        assertEquals(0, DesktopMacWindowChrome.topContentInsetDp(isMacOs = false))

        val rootPane = JRootPane()
        DesktopMacWindowChrome.applyToRootPane(rootPane)
        assertEquals(true, rootPane.getClientProperty("apple.awt.fullWindowContent"))
        assertEquals(true, rootPane.getClientProperty("apple.awt.transparentTitleBar"))
        assertEquals(false, rootPane.getClientProperty("apple.awt.windowTitleVisible"))
    }

    @Test
    fun `Rail upper capsule balances bottom label breathing room with top icon margin`() {
        assertEquals(8, SimpleRailCapsulePaddingPolicy.topPaddingDp)
        assertEquals(16, SimpleRailCapsulePaddingPolicy.bottomPaddingDp)
        assertTrue(SimpleRailCapsulePaddingPolicy.bottomPaddingDp > SimpleRailCapsulePaddingPolicy.topPaddingDp)
    }

    @Test
    fun `Active Home icon button uses soft translucent teal pill matching rail selection instead of solid neon`() {
        assertEquals(SimpleModernPalette.PillTint, SimpleTopBarHomeStyle.activeContainerColor)
        assertEquals(SimpleModernPalette.AccentMintSoft, SimpleTopBarHomeStyle.activeIconColor)
        assertEquals(SimpleModernPalette.AccentMint.copy(alpha = 0.45f), SimpleTopBarHomeStyle.activeBorderColor)
    }

    @Test
    fun `SimpleOverviewAutoRefreshPolicy skips auto-refresh when cached snapshot exists for device`() {
        assertTrue(
            SimpleOverviewAutoRefreshPolicy.shouldAutoFetchDetails(
                deviceId = "peer-1",
                online = true,
                cachedDetailsDeviceId = null,
                hasCachedSnapshot = false,
                alreadyLoading = false
            )
        )
        assertFalse(
            SimpleOverviewAutoRefreshPolicy.shouldAutoFetchDetails(
                deviceId = "peer-1",
                online = true,
                cachedDetailsDeviceId = "peer-1",
                hasCachedSnapshot = true,
                alreadyLoading = false
            )
        )
        assertTrue(
            SimpleOverviewAutoRefreshPolicy.shouldAutoFetchDetails(
                deviceId = "peer-2",
                online = true,
                cachedDetailsDeviceId = "peer-1",
                hasCachedSnapshot = true,
                alreadyLoading = false
            )
        )
        assertFalse(
            SimpleOverviewAutoRefreshPolicy.shouldAutoFetchDetails(
                deviceId = "peer-1",
                online = false,
                cachedDetailsDeviceId = null,
                hasCachedSnapshot = false,
                alreadyLoading = false
            )
        )
    }

    @Test
    fun `SimpleBottomBarLayoutPolicy centers two-line labels with symmetric edge spacing`() {
        assertEquals(2, SimpleBottomBarLayoutPolicy.labelMaxLines)
        assertEquals(76, SimpleBottomBarLayoutPolicy.curvedBarHeightDp)
        assertTrue(SimpleBottomBarLayoutPolicy.edgeSpacerDp > 0)
        assertFalse(SimpleBottomBarLayoutPolicy.showTopActionPills)
    }

    @Test
    fun `SimpleOverviewCardPolicy tailors drop card for phone, names Browse device, and enables inline status plus Devices card`() {
        assertFalse(SimpleOverviewCardPolicy.showDropHint(isDesktopHost = false))
        assertTrue(SimpleOverviewCardPolicy.showDropHint(isDesktopHost = true))
        assertTrue(
            SimpleOverviewCardPolicy.dropCardVerticalPaddingDp(isDesktopHost = false) <
                SimpleOverviewCardPolicy.dropCardVerticalPaddingDp(isDesktopHost = true)
        )
        assertEquals(
            "Browse Amanda",
            SimpleOverviewCardPolicy.browseDeviceLabel(
                deviceName = "Amanda",
                formattedLabel = "Browse Amanda",
                fallbackLabel = "Browse Device"
            )
        )
        assertEquals(
            "Browse Device",
            SimpleOverviewCardPolicy.browseDeviceLabel(
                deviceName = "  ",
                formattedLabel = "Browse   ",
                fallbackLabel = "Browse Device"
            )
        )
        assertTrue(SimpleOverviewCardPolicy.clipboardStatusInline(twoColumns = false))
        assertFalse(SimpleOverviewCardPolicy.clipboardStatusInline(twoColumns = true))
        assertTrue(SimpleOverviewCardPolicy.showDevicesCard(DesktopNavPosition.Bottom))
        assertFalse(SimpleOverviewCardPolicy.showDevicesCard(DesktopNavPosition.Left))
        assertFalse(SimpleOverviewCardPolicy.showDevicesCard(DesktopNavPosition.Right))
    }

    @Test
    fun `SimpleOverviewContentPolicy hides welcome QR while initial device load is pending`() {
        assertEquals(
            SimpleOverviewBodyState.Loading,
            SimpleOverviewContentPolicy.resolve(hasConnectedDevice = false, isInitialLoadComplete = false)
        )
        assertEquals(
            SimpleOverviewBodyState.Welcome,
            SimpleOverviewContentPolicy.resolve(hasConnectedDevice = false, isInitialLoadComplete = true)
        )
        assertEquals(
            SimpleOverviewBodyState.Connected,
            SimpleOverviewContentPolicy.resolve(hasConnectedDevice = true, isInitialLoadComplete = false)
        )
        assertEquals(
            SimpleOverviewBodyState.Connected,
            SimpleOverviewContentPolicy.resolve(hasConnectedDevice = true, isInitialLoadComplete = true)
        )
    }

    @Test
    fun `SimpleBackNavigationPolicy returns from Device details Info and empty Browse to Home instead of exiting app`() {
        assertFalse(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Home,
                hasBrowseTarget = false,
                hasOpenPopover = false
            )
        )
        assertTrue(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Info,
                hasBrowseTarget = false,
                hasOpenPopover = false
            )
        )
        assertTrue(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Settings,
                hasBrowseTarget = false,
                hasOpenPopover = false
            )
        )
        assertTrue(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Browse,
                hasBrowseTarget = false,
                hasOpenPopover = false
            )
        )
        assertFalse(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Browse,
                hasBrowseTarget = true,
                hasOpenPopover = false
            )
        )
        assertFalse(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.LocalFiles,
                hasBrowseTarget = false,
                hasOpenPopover = false
            )
        )
        assertTrue(
            SimpleBackNavigationPolicy.shouldInterceptSystemBack(
                destination = SimpleDestination.Home,
                hasBrowseTarget = false,
                hasOpenPopover = true
            )
        )
    }
}
