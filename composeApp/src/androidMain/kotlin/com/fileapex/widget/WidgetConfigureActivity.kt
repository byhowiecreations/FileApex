package com.fileapex.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fileapex.data.db.PairedDeviceEntity
import com.fileapex.di.FileApexServices
import com.fileapex.domain.widget.WidgetActionCatalog
import com.fileapex.domain.widget.WidgetActionTarget
import com.fileapex.domain.widget.WidgetConfig
import com.fileapex.domain.widget.WidgetKind
import com.fileapex.domain.widget.WidgetTargetBinding
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import kotlinx.coroutines.runBlocking

class WidgetConfigureActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show("Unlock device storage first")
            finish()
            return
        }

        val manager = AppWidgetManager.getInstance(this)
        val kind = WidgetUiFormatter.resolveKindForWidget(this, manager, appWidgetId)
            ?: WidgetKind.QUICK_ACTIONS
        val store = WidgetConfigStore(this)
        val existing = store.load(appWidgetId) ?: WidgetConfig(appWidgetId = appWidgetId, kind = kind)
        val devices = runBlocking { FileApexServices.deviceRepository.listDevices() }

        setContent {
            FileApexWidgetTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConfigureWidgetScreen(
                        kind = kind,
                        initial = existing,
                        devices = devices,
                        onCancel = { finish() },
                        onSave = { updated ->
                            store.save(updated)
                            WidgetUiFormatter.updateWidgetById(this, appWidgetId)
                            val resultIntent = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            setResult(Activity.RESULT_OK, resultIntent)
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfigureWidgetScreen(
    kind: WidgetKind,
    initial: WidgetConfig,
    devices: List<PairedDeviceEntity>,
    onCancel: () -> Unit,
    onSave: (WidgetConfig) -> Unit
) {
    val defaultBinding = when {
        kind == WidgetKind.DEVICE_CARD_2X2 && initial.binding !is WidgetTargetBinding.SpecificDevice && devices.isNotEmpty() ->
            WidgetTargetBinding.SpecificDevice(devices.first().deviceId)
        else -> initial.binding
    }
    var binding by remember { mutableStateOf(defaultBinding) }
    val selectedActions = remember {
        mutableStateListOf<WidgetActionTarget>().apply {
            addAll(initial.normalizedActions())
        }
    }
    var activeSlot by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = AppI18n.t("widget_configure_title"),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
        )

        if (kind.supportsTargetBinding) {
            Text(
                text = AppI18n.t("widget_configure_target_heading"),
                style = MaterialTheme.typography.titleMedium
            )
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    if (kind != WidgetKind.DEVICE_CARD_2X2) {
                        BindingOptionRow(
                            label = AppI18n.t("widget_target_ask"),
                            selected = binding is WidgetTargetBinding.AskEachTime,
                            onSelect = { binding = WidgetTargetBinding.AskEachTime }
                        )
                        BindingOptionRow(
                            label = AppI18n.t("widget_target_all_online"),
                            selected = binding is WidgetTargetBinding.AllOnline,
                            onSelect = { binding = WidgetTargetBinding.AllOnline }
                        )
                    }
                    devices.forEach { device ->
                        val specific = binding as? WidgetTargetBinding.SpecificDevice
                        BindingOptionRow(
                            label = device.deviceName.ifBlank { device.deviceId },
                            selected = specific?.deviceId == device.deviceId,
                            onSelect = { binding = WidgetTargetBinding.SpecificDevice(device.deviceId) }
                        )
                    }
                    if (devices.isEmpty()) {
                        Text(
                            text = AppI18n.t("widget_no_paired_devices"),
                            modifier = Modifier.padding(14.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        if (kind.maxActions > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                text = AppI18n.t("widget_configure_actions_heading", selectedActions.size),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                selectedActions.forEachIndexed { slot, assigned ->
                    val slotSelected = activeSlot == slot
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { activeSlot = slot },
                        shape = RoundedCornerShape(12.dp),
                        color = if (slotSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainer
                        },
                        border = BorderStroke(
                            width = if (slotSelected) 1.5.dp else 1.dp,
                            color = if (slotSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                painter = painterResource(WidgetUiFormatter.iconForAction(assigned)),
                                contentDescription = null,
                                tint = if (slotSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "${slot + 1}: ${AppI18n.t(assigned.labelKey)}",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = if (slotSelected) FontWeight.SemiBold else FontWeight.Medium
                                ),
                                color = if (slotSelected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedActions.size < kind.maxActions) {
                    OutlinedButton(
                        onClick = {
                            val nextAction = WidgetActionCatalog.allTargets()
                                .firstOrNull { it !in selectedActions }
                                ?: WidgetActionTarget.SEND_FILE
                            selectedActions.add(nextAction)
                            activeSlot = selectedActions.lastIndex
                        },
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
                    ) {
                        Text(AppI18n.t("widget_configure_add_action"))
                    }
                }
                if (selectedActions.size > 1) {
                    TextButton(
                        onClick = {
                            val removeIdx = activeSlot.coerceIn(0, selectedActions.lastIndex)
                            selectedActions.removeAt(removeIdx)
                            activeSlot = activeSlot.coerceAtMost(selectedActions.lastIndex).coerceAtLeast(0)
                        }
                    ) {
                        Text(AppI18n.t("widget_configure_remove_action"))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WidgetActionCatalog.allTargets().chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { action ->
                            val current = selectedActions.getOrNull(activeSlot) == action
                            WidgetActionOptionCard(
                                modifier = Modifier.weight(1f),
                                action = action,
                                selected = current,
                                onClick = {
                                    val idx = activeSlot.coerceIn(0, selectedActions.lastIndex)
                                    selectedActions[idx] = action
                                    if (idx + 1 < selectedActions.size) activeSlot = idx + 1
                                }
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(AppI18n.t("cancel"))
            }
            Button(
                onClick = {
                    onSave(
                        WidgetConfig(
                            appWidgetId = initial.appWidgetId,
                            kind = kind,
                            binding = binding,
                            actions = selectedActions.toList()
                        )
                    )
                }
            ) {
                Text(AppI18n.t("widget_configure_save"))
            }
        }
    }
}

@Composable
private fun BindingOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun WidgetActionOptionCard(
    modifier: Modifier = Modifier,
    action: WidgetActionTarget,
    selected: Boolean,
    onClick: () -> Unit
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val badgeColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val iconTint = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(WidgetUiFormatter.iconForAction(action)),
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                text = AppI18n.t(action.labelKey),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
