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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.WidgetActionCatalog
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Compact device picker displayed when a widget action is set to "Ask each time".
 */
class WidgetDevicePickActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show("Unlock device storage first")
            finish()
            return
        }
        val action = WidgetActionCatalog.byWireId(intent?.getStringExtra(WidgetDeepLinks.EXTRA_ACTION_WIRE_ID))
            ?: WidgetActionTarget.SEND_FILE
        val devices = runBlocking { FileApexServices.deviceRepository.listDevices() }
        val onlineIds = FileApexServices.presenceMonitor.onlineDeviceIds.value

        if (devices.isEmpty()) {
            BriefToast.show(AppI18n.t("widget_no_paired_devices"))
            finish()
            return
        }

        setContent {
            val scope = rememberCoroutineScope()
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
                                .padding(18.dp)
                        ) {
                            Text(
                                text = AppI18n.t("widget_choose_device_title"),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = AppI18n.t(action.labelKey),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            devices.forEach { device ->
                                val online = device.deviceId in onlineIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    AndroidWidgetActionDispatcher(LiveWidgetActionDeps(applicationContext))
                                                        .dispatch(action, WidgetTargetBinding.SpecificDevice(device.deviceId))
                                                }
                                                if (result is WidgetDispatchResult.Message) {
                                                    BriefToast.show(result.text)
                                                }
                                                finish()
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(if (online) Color(0xFF34D399) else Color(0xFF64748B))
                                    )
                                    Text(
                                        text = device.deviceName.ifBlank { device.deviceId },
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { finish() }) {
                                    Text(AppI18n.t("cancel"))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
