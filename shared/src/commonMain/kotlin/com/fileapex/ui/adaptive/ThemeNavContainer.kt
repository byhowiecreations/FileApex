package com.fileapex.ui.adaptive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.DesktopNavPosition
import com.fileapex.data.settings.DesktopNavPositionPolicy
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.data.settings.traits
import com.fileapex.di.FileApexServices
import com.fileapex.platform.StorageToolsEvents
import com.fileapex.platform.isDesktopHost
import com.fileapex.ui.HomeTab
import com.fileapex.ui.devices.FileApexBottomBar

/**
 * Compact shell scaffold placing [FileApexBottomBar] at the bottom or [FileApexNavigationRail] on the left or right edge.
 */
@Composable
internal fun ThemeCompactNavScaffold(
    selectedTab: HomeTab,
    onMainHomeScreen: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    content: @Composable () -> Unit
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme.traits.glassChrome
    val navPositionPref by FileApexServices.settings.desktopNavPosition.collectAsState()
    val navPosition = DesktopNavPositionPolicy.resolve(
        theme = currentTheme,
        isDesktop = isDesktopHost(),
        windowIsWide = false,
        desktopPosition = navPositionPref
    )
    val showBottomBar = !currentTheme.traits.canvasHome && navPosition == DesktopNavPosition.Bottom
    val showSideRail = !currentTheme.traits.canvasHome && navPosition != DesktopNavPosition.Bottom
    Scaffold(
        containerColor = if (isCustomGlass) Color.Transparent else MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                FileApexBottomBar(
                    selected = selectedTab,
                    onMainHomeScreen = onMainHomeScreen,
                    onDevices = onDevices,
                    onFiles = {
                        StorageToolsEvents.closeRequests.tryEmit(Unit)
                        onFiles()
                    },
                    onSettings = onSettings
                )
            }
        }
    ) { padding ->
        val bodyModifier = Modifier
            .fillMaxSize()
            .then(if (currentTheme.traits.canvasHome) Modifier else Modifier.padding(padding))
        if (showSideRail) {
            ThemeNavRailRow(
                navPosition = navPosition,
                selected = selectedTab,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = onFiles,
                onSettings = onSettings,
                modifier = bodyModifier,
                content = content
            )
        } else {
            Column(modifier = bodyModifier) { content() }
        }
    }
}

/**
 * Places the theme's navigation rail (left or right) or bottom bar around [content].
 */
@Composable
internal fun ThemeNavContainer(
    navPosition: DesktopNavPosition,
    selected: HomeTab,
    onMainHomeScreen: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    showNav: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val handleFiles: () -> Unit = {
        StorageToolsEvents.closeRequests.tryEmit(Unit)
        onFiles()
    }
    if (!showNav) {
        Row(modifier = modifier.fillMaxWidth()) { content() }
        return
    }
    when (navPosition) {
        DesktopNavPosition.Left -> Row(modifier = modifier.fillMaxWidth()) {
            FileApexNavigationRail(
                selected = selected,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = handleFiles,
                onSettings = onSettings,
                onRightEdge = false
            )
            Row(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
        }
        DesktopNavPosition.Right -> Row(modifier = modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f).fillMaxHeight()) { content() }
            FileApexNavigationRail(
                selected = selected,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = handleFiles,
                onSettings = onSettings,
                onRightEdge = true
            )
        }
        DesktopNavPosition.Bottom -> Column(modifier = modifier.fillMaxWidth()) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
            FileApexBottomBar(
                selected = selected,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = handleFiles,
                onSettings = onSettings
            )
        }
    }
}

/**
 * Renders a left or right navigation rail beside full-height compact shell content.
 */
@Composable
internal fun ThemeNavRailRow(
    navPosition: DesktopNavPosition,
    selected: HomeTab,
    onMainHomeScreen: Boolean,
    onDevices: () -> Unit,
    onFiles: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val handleFiles: () -> Unit = {
        StorageToolsEvents.closeRequests.tryEmit(Unit)
        onFiles()
    }
    Row(modifier = modifier.fillMaxSize()) {
        if (navPosition == DesktopNavPosition.Left) {
            FileApexNavigationRail(
                selected = selected,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = handleFiles,
                onSettings = onSettings,
                onRightEdge = false
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            content()
        }
        if (navPosition == DesktopNavPosition.Right) {
            FileApexNavigationRail(
                selected = selected,
                onMainHomeScreen = onMainHomeScreen,
                onDevices = onDevices,
                onFiles = handleFiles,
                onSettings = onSettings,
                onRightEdge = true
            )
        }
    }
}
