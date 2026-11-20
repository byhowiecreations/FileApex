package com.fileapex.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.journeyapps.barcodescanner.camera.CameraSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingQrScannerConfigTest {

    @Test
    fun `camera settings enable continuous autofocus instead of slow periodic auto`() {
        val settings = pairingQrCameraSettings()
        assertTrue("Continuous focus avoids 2s autofocus hunts", settings.isContinuousFocusEnabled)
        assertEquals(CameraSettings.FocusMode.CONTINUOUS, settings.focusMode)
    }

    @Test
    fun `decode hints enable try harder for dense pairing qr codes`() {
        val hints = pairingQrDecodeHints()
        assertEquals(true, hints[DecodeHintType.TRY_HARDER])
    }

    @Test
    fun `decoder factory creates a qr-only decoder using pairing hints`() {
        val factory = pairingQrDecoderFactory()
        val decoder = factory.createDecoder(emptyMap<DecodeHintType, Any>())
        assertNotNull(decoder)
        assertEquals(listOf(BarcodeFormat.QR_CODE), PAIRING_QR_FORMATS)
    }

    @Test
    fun `scanner uses full visible box and texture view without growing layout height`() {
        assertEquals(0.0, PAIRING_QR_MARGIN_FRACTION, 0.0)
        assertTrue(PAIRING_QR_USE_TEXTURE_VIEW)
    }
}
