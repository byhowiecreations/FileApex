package com.fileapex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fileapex.domain.pairing.PairingPayload
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

@Composable
fun PairingQrPanel(
    payload: PairingPayload,
    modifier: Modifier = Modifier
) {
    PairingQrPanel(qrText = payload.toQrText(), payload = payload, modifier = modifier)
}

@Composable
fun PairingQrPanel(
    qrText: String,
    payload: PairingPayload,
    modifier: Modifier = Modifier
) {
    val painter = rememberQrCodePainter(qrText) {
        errorCorrectionLevel = QrErrorCorrectionLevel.Medium
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Pair with this node",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "${payload.deviceName}\n${payload.host}:${payload.port}",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Image(
            painter = painter,
            contentDescription = "FileApex pairing QR code",
            modifier = Modifier
                .size(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(12.dp)
        )
        Text(
            text = "Scan with Camera and tap Open FileApex, or Add New Device → Scan QR Code. " +
                "Some Motorola phones require Scan QR Code inside FileApex.",
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
