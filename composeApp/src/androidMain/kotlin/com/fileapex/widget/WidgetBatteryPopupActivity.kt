package com.fileapex.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.CachedBatteryReading
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.presentation.devices.PeerBatteryFetch
import com.fileapex.util.TimeUtils
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Translucent popup that polls all paired devices concurrently and displays live battery readings
 * in one tap without requiring an extra device-selection step.
 */
class WidgetBatteryPopupActivity : ComponentActivity() {

    private sealed interface DeviceBatteryRowState {
        data object Loading : DeviceBatteryRowState
        data class Ready(val levelPercent: Int, val chargingState: String) : DeviceBatteryRowState
        data object Unavailable : DeviceBatteryRowState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show("Unlock device storage first")
            finish()
            return
        }
        val preferredDeviceId = intent?.getStringExtra(WidgetDeepLinks.EXTRA_PREFERRED_DEVICE_ID)
            ?.trim()?.takeIf { it.isNotEmpty() }

        setContent {
            var refreshTick by remember { mutableIntStateOf(0) }
            var devices by remember { mutableStateOf<List<PairedDeviceEntity>>(emptyList()) }
            val rowStates = remember { mutableStateMapOf<String, DeviceBatteryRowState>() }
            var refreshing by remember { mutableStateOf(true) }

            LaunchedEffect(refreshTick) {
                refreshing = true
                val loaded = withContext(Dispatchers.IO) {
                    val all = FileApexServices.deviceRepository.listDevices()
                    if (preferredDeviceId == null) {
                        all
                    } else {
                        all.sortedByDescending { it.deviceId == preferredDeviceId }
                    }
                }
                devices = loaded
                if (loaded.isEmpty()) {
                    refreshing = false
                    return@LaunchedEffect
                }
                loaded.forEach { rowStates[it.deviceId] = DeviceBatteryRowState.Loading }
                val updatedAny = withContext(Dispatchers.IO) {
                    val store = WidgetConfigStore(applicationContext)
                    var anySaved = false
                    coroutineScope {
                        loaded.map { device ->
                            async {
                                val diag = cancellableCatching { PeerBatteryFetch.fetch(device) }.getOrNull()
                                val pct = diag?.levelPercent
                                if (pct != null && pct in 0..100) {
                                    store.saveBatteryReading(
                                        deviceId = device.deviceId,
                                        reading = CachedBatteryReading(
                                            levelPercent = pct,
                                            recordedAtEpochMs = TimeUtils.now(),
                                            chargingState = diag.chargingState
                                        )
                                    )
                                    anySaved = true
                                    withContext(Dispatchers.Main) {
                                        rowStates[device.deviceId] = DeviceBatteryRowState.Ready(
                                            levelPercent = pct,
                                            chargingState = diag.chargingState
                                        )
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        rowStates[device.deviceId] = DeviceBatteryRowState.Unavailable
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    anySaved
                }
                if (updatedAny) {
                    withContext(Dispatchers.IO) {
                        WidgetUiFormatter.updateAllWidgets(applicationContext)
                    }
                }
                refreshing = false
            }

            FileApexWidgetTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.48f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { finish() }
                        )
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .widthIn(max = 380.dp)
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            ),
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .verticalScroll(rememberScrollState())
                                .padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = AppI18n.t("widget_battery_popup_title"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            HorizontalDivider()
                            if (devices.isEmpty() && !refreshing) {
                                Text(
                                    text = AppI18n.t("widget_no_paired_devices"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                devices.forEach { device ->
                                    val state = rowStates[device.deviceId] ?: DeviceBatteryRowState.Loading
                                    val isOnline = state is DeviceBatteryRowState.Ready ||
                                        FileApexServices.presenceMonitor.onlineDeviceIds.value.contains(device.deviceId)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (isOnline) Color(0xFF34D399) else Color(0xFF64748B))
                                        )
                                        Text(
                                            text = device.deviceName.ifBlank { device.deviceId },
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        when (state) {
                                            DeviceBatteryRowState.Loading -> {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(16.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            }
                                            is DeviceBatteryRowState.Ready -> {
                                                val label = if (state.chargingState.isNotBlank()) {
                                                    AppI18n.t("widget_battery_charging_fmt", state.levelPercent, state.chargingState)
                                                } else {
                                                    AppI18n.t("widget_battery_pct_only", state.levelPercent)
                                                }
                                                Text(
                                                    text = label,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = Color(0xFF38BDF8)
                                                )
                                            }
                                            DeviceBatteryRowState.Unavailable -> {
                                                Text(
                                                    text = AppI18n.t("widget_device_offline"),
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { if (!refreshing) refreshTick++ },
                                    enabled = !refreshing
                                ) {
                                    Text(AppI18n.t("widget_battery_refresh"))
                                }
                                TextButton(onClick = { finish() }) {
                                    Text(AppI18n.t("ok"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
