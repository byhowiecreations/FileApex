package com.fileapex.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.DesktopUiStyle
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.ui.theme.FileApexTheme
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrPanelSpecTest {

    @Test
    fun `pairing qr uses low error correction and compact dimensions with quiet zone`() {
        assertEquals(QrErrorCorrectionLevel.Low, PAIRING_QR_ERROR_CORRECTION)
        assertTrue(
            "QR box should stay compact (200-240dp), was $PAIRING_QR_BOX_SIZE_DP",
            PAIRING_QR_BOX_SIZE_DP in 200.dp..240.dp
        )
        assertTrue(
            "QR quiet zone should be at least 12dp, was $PAIRING_QR_QUIET_ZONE_DP",
            PAIRING_QR_QUIET_ZONE_DP >= 12.dp
        )
    }

    @Test
    fun `pairing qr panel renders realistic full tls pairing payload cleanly`() {
        val payload = PairingPayload(
            deviceId = "fx-0123456789abcdef0123456789abcdef",
            deviceName = "MacBook Pro 16",
            host = "192.168.1.130",
            port = 48291,
            rootPath = "/Users/cliff",
            tlsPin = "a".repeat(64),
            tlsPort = 48292,
            pairingSecret = "b".repeat(32)
        )
        val scene = ImageComposeScene(width = 480, height = 640, density = Density(1f)) {
            FileApexTheme(uiStyle = DesktopUiStyle.Standard, appTheme = AppTheme.SIMPLE) {
                PairingQrPanel(payload = payload)
            }
        }
        try {
            scene.render(16_000_000L)
        } finally {
            scene.close()
        }
    }
}
