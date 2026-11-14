package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.domain.pairing.PairingBeacon
import com.fileapex.i18n.stringRes
import com.fileapex.presentation.DevicesViewModel
import com.fileapex.util.NetworkUtils

/** Auto-discovers nearby pairing broadcasts strictly while a device has zero paired peers. */
object UnpairedLanDiscoveryPolicy {
    fun shouldAutoDiscover(
        pairedRowsCount: Int,
        isDemoActive: Boolean,
        isInitialLoadComplete: Boolean
    ): Boolean = isInitialLoadComplete && pairedRowsCount == 0 && !isDemoActive

    fun visibleDiscoveredPeers(
        shouldDiscover: Boolean,
        peers: List<PairingBeacon>
    ): List<PairingBeacon> = if (shouldDiscover) peers else emptyList()
}

@Composable
fun UnpairedLanDiscoverySection(
    devicesViewModel: DevicesViewModel,
    pairedRowsCount: Int,
    isInitialLoadComplete: Boolean,
    isDemoActive: Boolean,
    modifier: Modifier = Modifier
) {
    val shouldDiscover = UnpairedLanDiscoveryPolicy.shouldAutoDiscover(
        pairedRowsCount = pairedRowsCount,
        isDemoActive = isDemoActive,
        isInitialLoadComplete = isInitialLoadComplete
    )
    DisposableEffect(shouldDiscover, devicesViewModel) {
        if (shouldDiscover) {
            devicesViewModel.startPairingDiscovery()
        }
        onDispose {
            if (shouldDiscover) {
                devicesViewModel.stopPairingDiscovery()
            }
        }
    }
    val state by devicesViewModel.uiState.collectAsState()
    val visiblePeers = UnpairedLanDiscoveryPolicy.visibleDiscoveredPeers(
        shouldDiscover = shouldDiscover,
        peers = state.discoveredPairingPeers
    )
    if (visiblePeers.isNotEmpty()) {
        NearbyDiscoveredPeersBanner(
            peers = visiblePeers,
            isWorking = state.pendingPinPairing != null,
            onPairPeer = { peer -> devicesViewModel.pairFromQrPayload(peer.toPairingPayload()) },
            modifier = modifier
        )
    }
}

@Composable
fun NearbyDiscoveredPeersBanner(
    peers: List<PairingBeacon>,
    isWorking: Boolean,
    onPairPeer: (PairingBeacon) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Devices,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringRes("nearby_devices_ready"),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringRes("nearby_devices_pair_desc"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            peers.forEach { peer ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer.deviceName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = NetworkUtils.formatEndpointDisplay(peer.ipAddress, peer.port),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { onPairPeer(peer) },
                        enabled = !isWorking,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(stringRes("pair"))
                    }
                }
            }
        }
    }
}
