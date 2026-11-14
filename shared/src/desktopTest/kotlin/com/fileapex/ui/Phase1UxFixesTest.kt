package com.fileapex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.sharp.ExitToApp
import androidx.compose.material.icons.automirrored.twotone.ExitToApp
import androidx.compose.material.icons.outlined.Link
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Density
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.i18n.AppI18n
import com.fileapex.ui.adaptive.simple.ClipboardActionCard
import com.fileapex.ui.adaptive.simple.SimpleBottomBarLayoutPolicy
import com.fileapex.ui.adaptive.simple.SimpleChromeIcons
import com.fileapex.ui.adaptive.simple.SimpleWelcomeLayoutPolicy
import com.fileapex.ui.adaptive.simple.SimpleWelcomeOverview
import com.fileapex.ui.adaptive.simple.SimpleWelcomeStartupPolicy
import com.fileapex.ui.adaptive.simple.resolveClipboardCardClickAction
import com.fileapex.ui.theme.FileApexTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase1UxFixesTest {

    @Test
    fun `quit icon uses ExitToApp instead of device PowerSettingsNew across all icon families`() {
        assertEquals(Icons.AutoMirrored.Outlined.ExitToApp, explorerIcon(ExplorerIcon.Power, ExplorerIconFamily.Outlined))
        assertEquals(Icons.AutoMirrored.Rounded.ExitToApp, explorerIcon(ExplorerIcon.Power, ExplorerIconFamily.Rounded))
        assertEquals(Icons.AutoMirrored.Sharp.ExitToApp, explorerIcon(ExplorerIcon.Power, ExplorerIconFamily.Sharp))
        assertEquals(Icons.AutoMirrored.TwoTone.ExitToApp, explorerIcon(ExplorerIcon.Power, ExplorerIconFamily.TwoTone))
    }

    @Test
    fun `simple chrome and devices card use device icon instead of duplicating Link icon`() {
        assertNotEquals(Icons.Outlined.Link, SimpleChromeIcons.devicesCardIcon)
        assertNotEquals(Icons.Outlined.Link, SimpleChromeIcons.deviceManagementIcon)
    }

    @Test
    fun `simple welcome tutorial modal does not auto pop on startup`() {
        assertFalse(SimpleWelcomeStartupPolicy.shouldAutoShowTutorialModal())
    }

    @Test
    fun `join cluster safety requires confirmation when paired devices exist and ignores demo peers`() {
        assertEquals(0, JoinClusterSafetyPolicy.pairedPeerCount(rowsCount = 2, isDemoActive = true))
        assertEquals(2, JoinClusterSafetyPolicy.pairedPeerCount(rowsCount = 2, isDemoActive = false))
        assertFalse(JoinClusterSafetyPolicy.requiresLeaveConfirmation(pairedCount = 0))
        assertTrue(JoinClusterSafetyPolicy.requiresLeaveConfirmation(pairedCount = 1))
        assertTrue(JoinClusterSafetyPolicy.requiresLeaveConfirmation(pairedCount = 3))
    }

    @Test
    fun `live qr desktop hint does not ask user to type a 6 digit code before connecting`() {
        val hint = AppI18n.t("live_qr_desktop_hint")
        assertFalse("Desktop QR hint should not mention entering a 6-digit code: $hint", hint.contains("6-digit"))
        assertFalse("Desktop QR hint should not mention 6 digit code: $hint", hint.contains("6 digit"))
    }

    @Test
    fun `clipboard action card invokes click handler even when clipboard sharing is already enabled`() {
        var clicks = 0
        val scene = ImageComposeScene(
            width = 360,
            height = 140,
            density = Density(1f)
        ) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                ClipboardActionCard(
                    sharingEnabled = true,
                    promptHint = null,
                    onClick = { clicks++ },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        try {
            scene.render(0L)
            scene.sendPointerEvent(
                eventType = PointerEventType.Press,
                position = Offset(180f, 40f),
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
                timeMillis = 10L
            )
            scene.sendPointerEvent(
                eventType = PointerEventType.Release,
                position = Offset(180f, 40f),
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = false),
                timeMillis = 30L
            )
            scene.render(40_000_000L)
            assertEquals("ClipboardActionCard should trigger send action when enabled", 1, clicks)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `pairing qr panel and simple welcome overview render without crashing across collapsed and equal-height states`() {
        val qrScene = ImageComposeScene(width = 480, height = 640, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                PairingQrPanel(
                    qrText = "fileapex://pair?host=10.0.0.5&port=8443",
                    payload = PairingPayload(
                        deviceId = "fx-host-1",
                        deviceName = "Studio Mac",
                        host = "10.0.0.5",
                        port = 8443,
                        rootPath = "/Users/cliff",
                        tlsPin = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                    ),
                    broadcastStatusText = "Broadcasting on 239.255.0.89:8891",
                    onCopyPairingLink = {}
                )
            }
        }
        val welcomeScene = ImageComposeScene(width = 800, height = 640, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleWelcomeOverview(
                    onAddDevice = {},
                    onJoinDevice = {},
                    onLaunchDemo = {},
                    onShareAsLink = {},
                    onOpenBulletinBoard = {},
                    onHowItWorks = {},
                    isDesktopOverride = true
                )
            }
        }
        val androidOuterScene = ImageComposeScene(width = 360, height = 640, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                SimpleWelcomeOverview(
                    onAddDevice = {},
                    onJoinDevice = {},
                    onLaunchDemo = {},
                    onShareAsLink = {},
                    onOpenBulletinBoard = {},
                    onHowItWorks = {},
                    isDesktopOverride = false
                )
            }
        }
        try {
            qrScene.render(0L).close()
            welcomeScene.render(0L).close()
            androidOuterScene.render(0L).close()
        } finally {
            qrScene.close()
            welcomeScene.close()
            androidOuterScene.close()
        }
    }

    @Test
    fun `simple clipboard click sends clipboard when enabled and prompts setup when disabled`() {
        var sent = 0
        var prompted = 0
        resolveClipboardCardClickAction(
            sharingEnabled = true,
            onSendClipboard = { sent++ },
            onPromptSetup = { prompted++ }
        )()
        assertEquals(1, sent)
        assertEquals(0, prompted)

        resolveClipboardCardClickAction(
            sharingEnabled = false,
            onSendClipboard = { sent++ },
            onPromptSetup = { prompted++ }
        )()
        assertEquals(1, sent)
        assertEquals(1, prompted)
    }

    @Test
    fun `android hides bulletin card and nav tab while pairing qr copy stays concise`() {
        assertFalse(SimpleWelcomeLayoutPolicy.showBulletinCard(isDesktop = false))
        assertTrue(SimpleWelcomeLayoutPolicy.showBulletinCard(isDesktop = true))
        assertFalse(SimpleBottomBarLayoutPolicy.showBulletinTab(isDesktop = false))
        assertTrue(SimpleBottomBarLayoutPolicy.showBulletinTab(isDesktop = true))
        assertTrue(SimpleWelcomeLayoutPolicy.soloCardsSideBySide(isDesktop = false, availableWidthDp = 320f))

        val qrIntro = AppI18n.t("qr_intro")
        assertTrue("qr_intro is still too wordy (${qrIntro.length} chars): $qrIntro", qrIntro.length <= 120)
    }
}
