package com.fileapex.ui

import com.fileapex.ui.motion.FileApexMotion
import com.fileapex.ui.motion.LocalReducedMotion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fileapex.domain.pairing.PairingPayload
import com.fileapex.i18n.stringRes
import com.fileapex.platform.PlatformClipboard
import com.fileapex.util.NetworkUtils
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.rememberQrCodePainter

internal val PAIRING_QR_ERROR_CORRECTION = QrErrorCorrectionLevel.Low
internal val PAIRING_QR_BOX_SIZE_DP = 220.dp
internal val PAIRING_QR_QUIET_ZONE_DP = 14.dp

@Composable
fun PairingQrPanel(
    payload: PairingPayload,
    modifier: Modifier = Modifier,
    broadcastStatusText: String? = null
) {
    PairingQrPanel(
        qrText = payload.toQrText(),
        payload = payload,
        modifier = modifier,
        broadcastStatusText = broadcastStatusText
    )
}

@Composable
fun PairingQrPanel(
    qrText: String,
    payload: PairingPayload,
    modifier: Modifier = Modifier,
    broadcastStatusText: String? = null,
    showScanHint: Boolean = true,
    onCopyPairingLink: (String) -> Unit = { PlatformClipboard.setSystemClipboardText(it) }
) {
    val painter = rememberQrCodePainter(qrText) {
        errorCorrectionLevel = PAIRING_QR_ERROR_CORRECTION
    }
    var linkCopied by remember(qrText) { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

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
            text = stringRes("pair_with_this_node"),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "${payload.deviceName}\n${NetworkUtils.formatEndpointDisplay(payload.host, payload.port)}",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Image(
            painter = painter,
            contentDescription = stringRes("fileapex_pairing_qr"),
            modifier = Modifier
                .size(PAIRING_QR_BOX_SIZE_DP)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White)
                .padding(PAIRING_QR_QUIET_ZONE_DP)
        )
        if (showScanHint) {
            Text(
                text = stringRes("scan_qr_pairing_hint"),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(
            onClick = {
                onCopyPairingLink(qrText)
                linkCopied = true
            }
        ) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringRes(if (linkCopied) "pairing_link_copied" else "copy_pairing_link"))
        }
        val hasDetails = !broadcastStatusText.isNullOrBlank() || payload.tlsPin.isNotBlank()
        if (hasDetails) {
            TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringRes("connection_security_details"),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Icon(
                        imageVector = if (detailsExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            AnimatedVisibility(
                visible = detailsExpanded,
                enter = FileApexMotion.expandIn(LocalReducedMotion.current),
                exit = FileApexMotion.shrinkOut(LocalReducedMotion.current)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (!broadcastStatusText.isNullOrBlank()) {
                        Text(
                            text = broadcastStatusText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (payload.tlsPin.isNotBlank()) {
                        Text(
                            text = "${stringRes("tls_fingerprint_label")}: ${com.fileapex.security.tls.TlsPinWords.of(payload.tlsPin)} (${com.fileapex.security.tls.TlsFingerprint.short(payload.tlsPin)})",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
