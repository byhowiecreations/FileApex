package com.fileapex.ui.adaptive.simple

import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.presentation.ExplorerViewMode
import com.fileapex.ui.adaptive.SimpleDestination
import com.fileapex.ui.adaptive.SimpleRailEntry

/**
 * Modern dark slate-obsidian palette with mint-teal accents for the redesigned Simple theme.
 */
object SimpleModernPalette {
    val BackgroundDark = Color(0xFF070C0F)
    val GlowTeal = Color(0xFF176156)
    val GlowMid = Color(0xFF0C2225)

    val ChromeSurface = Color(0xA6132529)
    val ChromeBorder = Color(0x4784B8B2)
    val ChromeContent = Color(0xFFF3F8F8)

    val CardSurface = Color(0x88122024)
    val CardBorder = Color(0x3D6E9395)
    val DropZoneTop = Color(0xBD1B524C)
    val DropZoneBottom = Color(0x8E102327)
    val DropZoneHover = Color(0x4033DFC4)

    val AccentMint = Color(0xFF33DFC4)
    val AccentMintSoft = Color(0xFF6AEBD6)
    val AccentOnMint = Color(0xFF06211E)
    val PillTint = Color(0x362DD4BF)
    val StatusGreenPill = Color(0x2B22C55E)
    val StatusGreenText = Color(0xFF4ADE80)
    val TextPrimary = Color(0xFFF3F8F8)
    val TextSecondary = Color(0xFFA7BDBE)
    val CounterPill = Color(0xFF1A2A2F)
}

enum class SimpleOverviewScreenMode {
    Welcome,
    ConnectedNotifSetup,
    ConnectedNotifActive
}

object SimpleOverviewStateResolver {
    fun resolve(
        hasAnyPairedDevice: Boolean,
        hasConnectedDevice: Boolean,
        notificationReady: Boolean
    ): SimpleOverviewScreenMode = when {
        !hasAnyPairedDevice || !hasConnectedDevice -> SimpleOverviewScreenMode.Welcome
        notificationReady -> SimpleOverviewScreenMode.ConnectedNotifActive
        else -> SimpleOverviewScreenMode.ConnectedNotifSetup
    }
}

object SimpleNavPlacementPolicy {
    fun resolve(
        isDesktop: Boolean,
        windowIsWide: Boolean,
        desktopPosition: DesktopNavPosition
    ): DesktopNavPosition = DesktopNavPositionPolicy.resolve(
        theme = AppTheme.SIMPLE,
        isDesktop = isDesktop,
        windowIsWide = windowIsWide,
        desktopPosition = desktopPosition
    )
}

object SimpleSplitViewPolicy {
    fun isSplitSupported(destination: SimpleDestination): Boolean =
        destination == SimpleDestination.LocalFiles || destination == SimpleDestination.Browse

    fun overviewSplitDestination(): SimpleDestination = SimpleDestination.Browse
}

object SimpleRailCatalog {
    fun primaryEntries(): List<SimpleRailEntry> = listOf(
        SimpleRailEntry.LocalFiles,
        SimpleRailEntry.Browse,
        SimpleRailEntry.BulletinBoard,
        SimpleRailEntry.Settings
    )

    fun labelKey(entry: SimpleRailEntry): String = when (entry) {
        SimpleRailEntry.LocalFiles -> "local_files"
        SimpleRailEntry.Browse -> "remote_files"
        SimpleRailEntry.Clipboard -> "clipboard"
        SimpleRailEntry.BulletinBoard -> "bulletin_board"
        SimpleRailEntry.Settings -> "settings"
    }
}

object SimpleHeaderLayoutPolicy {
    fun fitsSideBySide(
        availableWidthPx: Float,
        leftWidthPx: Float,
        rightWidthPx: Float,
        minGapPx: Float
    ): Boolean = leftWidthPx + minGapPx + rightWidthPx <= availableWidthPx
}

object SimplePopoverPlacementPolicy {
    fun maxContentHeightPx(windowHeightPx: Float, marginPx: Float = 8f): Float =
        (windowHeightPx - marginPx * 2f).coerceAtLeast(0f)

    fun computeVerticalOffsetPx(
        anchorTopPx: Float,
        windowHeightPx: Float,
        contentHeightPx: Float,
        marginPx: Float = 8f
    ): Float {
        val available = maxContentHeightPx(windowHeightPx, marginPx)
        val fittedHeight = contentHeightPx.coerceAtMost(available)
        val maxTop = (windowHeightPx - fittedHeight - marginPx).coerceAtLeast(marginPx)
        return anchorTopPx.coerceIn(marginPx, maxTop)
    }
}

object SimpleRailLabelPolicy {
    /** Rail labels wrap past this width so they keep a margin inside the rail capsule. */
    const val MAX_LABEL_WIDTH_DP = 68
}

object SimpleRailCapsulePaddingPolicy {
    const val topPaddingDp = 8
    const val bottomPaddingDp = 16
}

object SimpleTopBarHomeStyle {
    val activeContainerColor = SimpleModernPalette.PillTint
    val activeIconColor = SimpleModernPalette.AccentMintSoft
    val activeBorderColor = SimpleModernPalette.AccentMint.copy(alpha = 0.45f)
}

/**
 * Tracks a temporary Split View override when launched from Overview's Browse Device button
 * so the remembered ExplorerViewMode for SimpleRail (Browse / Local Files) stays intact.
 */
class SimpleHomeSplitState {
    private var savedNonSplitMode: ExplorerViewMode? = null

    fun launchFromHome(
        currentMode: ExplorerViewMode,
        setViewMode: (ExplorerViewMode) -> Unit
    ): SimpleDestination {
        if (currentMode != ExplorerViewMode.Split) {
            savedNonSplitMode = currentMode
            setViewMode(ExplorerViewMode.Split)
        }
        return SimpleSplitViewPolicy.overviewSplitDestination()
    }

    fun onViewModeChanged(newMode: ExplorerViewMode) {
        if (newMode != ExplorerViewMode.Split) {
            savedNonSplitMode = null
        }
    }

    fun onNavigateAway(
        currentMode: ExplorerViewMode,
        setViewMode: (ExplorerViewMode) -> Unit
    ) {
        val restore = savedNonSplitMode ?: return
        savedNonSplitMode = null
        if (currentMode == ExplorerViewMode.Split) {
            setViewMode(restore)
        }
    }
}

object SimpleOverviewAutoRefreshPolicy {
    fun shouldAutoFetchDetails(
        deviceId: String?,
        online: Boolean,
        cachedDetailsDeviceId: String?,
        hasCachedSnapshot: Boolean,
        alreadyLoading: Boolean
    ): Boolean = deviceId != null &&
        online &&
        !alreadyLoading &&
        (cachedDetailsDeviceId != deviceId || !hasCachedSnapshot)
}

object SimpleBottomBarLayoutPolicy {
    const val labelMaxLines = 2
    const val curvedBarHeightDp = 76
    const val edgeSpacerDp = 4
    const val showTopActionPills = false

    fun tabs(): List<SimpleRailEntry> = listOf(
        SimpleRailEntry.LocalFiles,
        SimpleRailEntry.Browse,
        SimpleRailEntry.BulletinBoard,
        SimpleRailEntry.Settings
    )
}

enum class OverviewCard { Browse, Share, Clipboard, Locate, Devices }

object SimpleOverviewCardPolicy {
    fun clipboardStatusInline(twoColumns: Boolean): Boolean = !twoColumns

    fun useCompactSendRow(isDesktopHost: Boolean): Boolean = !isDesktopHost

    fun compactDropRowVerticalPaddingDp(): Int = 11

    /** A desktop window wide enough for two card columns puts Share as a link in the right half of the drop row. */
    fun shareBesideDrop(isDesktopHost: Boolean, contentWidthDp: Float): Boolean =
        isDesktopHost && contentWidthDp >= SHARE_BESIDE_DROP_MIN_WIDTH_DP

    private const val SHARE_BESIDE_DROP_MIN_WIDTH_DP = 420f

    /** Width weights of the split row: the drop area keeps two thirds, Share as a link a small card beside it. */
    fun dropRowWeights(): Pair<Float, Float> = 2f to 1f

    /** The cards under the drop area, row by row. Devices is always one of them: the rail has no Devices entry. */
    fun cardRows(isPhonePeer: Boolean, twoColumns: Boolean, shareBesideDrop: Boolean): List<List<OverviewCard>> = when {
        !twoColumns -> listOfNotNull(
            OverviewCard.Browse,
            OverviewCard.Share,
            OverviewCard.Clipboard,
            OverviewCard.Devices,
            OverviewCard.Locate.takeIf { isPhonePeer }
        ).map { listOf(it) }
        shareBesideDrop -> listOf(
            listOf(OverviewCard.Browse, OverviewCard.Devices),
            if (isPhonePeer) listOf(OverviewCard.Clipboard, OverviewCard.Locate) else listOf(OverviewCard.Clipboard)
        )
        isPhonePeer -> listOf(
            listOf(OverviewCard.Browse, OverviewCard.Share),
            listOf(OverviewCard.Clipboard, OverviewCard.Locate),
            listOf(OverviewCard.Devices)
        )
        else -> listOf(
            listOf(OverviewCard.Browse, OverviewCard.Share),
            listOf(OverviewCard.Clipboard, OverviewCard.Devices)
        )
    }

    fun browseDeviceLabel(
        deviceName: String,
        formattedLabel: String,
        fallbackLabel: String
    ): String = if (deviceName.isNotBlank()) formattedLabel else fallbackLabel
}

enum class SimpleOverviewBodyState {
    Loading,
    Welcome,
    Connected
}

object SimpleOverviewContentPolicy {
    fun resolve(hasConnectedDevice: Boolean, isInitialLoadComplete: Boolean): SimpleOverviewBodyState = when {
        hasConnectedDevice -> SimpleOverviewBodyState.Connected
        isInitialLoadComplete -> SimpleOverviewBodyState.Welcome
        else -> SimpleOverviewBodyState.Loading
    }
}

object SimpleBackNavigationPolicy {
    fun shouldInterceptSystemBack(
        destination: SimpleDestination,
        hasBrowseTarget: Boolean,
        hasOpenPopover: Boolean
    ): Boolean = hasOpenPopover || when (destination) {
        SimpleDestination.Home,
        SimpleDestination.LocalFiles -> false
        SimpleDestination.Info,
        SimpleDestination.Settings -> true
        SimpleDestination.Browse -> !hasBrowseTarget
    }
}
