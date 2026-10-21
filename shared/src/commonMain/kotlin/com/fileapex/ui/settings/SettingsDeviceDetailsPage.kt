package com.fileapex.ui.settings

import com.fileapex.ui.DeviceOrderDragState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.diagnostics.DeviceDetailsDisplayPreferences
import com.fileapex.domain.diagnostics.DeviceDetailsFieldId
import com.fileapex.i18n.stringRes
import com.fileapex.ui.DeviceOrderDragHandle
import com.fileapex.ui.DeviceOrderEdgeAutoScrollEffect
import com.fileapex.ui.SettingsScreenLayoutMode
import com.fileapex.ui.deviceOrderItemVisualOffsetPx
import com.fileapex.ui.rememberDeviceOrderDragState
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DeviceDetailsSettingsPage(
    preferences: DeviceDetailsDisplayPreferences,
    allowOverCellular: Boolean,
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit,
    onBeforeAllowOverCellularEnabled: (onProceed: () -> Unit) -> Unit,
    onAllowOverCellularChange: (Boolean) -> Unit,
    onFieldVisibleChange: (DeviceDetailsFieldId, Boolean) -> Unit,
    onReorderFields: (fromIndex: Int, toIndex: Int) -> Unit,
    onReset: () -> Unit
) {
    val normalized = preferences.normalized()
    val fieldEntries = normalized.fields.mapNotNull { pref ->
        DeviceDetailsFieldId.entries.find { it.name == pref.id }
            ?.takeUnless { com.fileapex.di.FileApexServices.isPlayStoreBuild && it.cellularOnly }
            ?.let { id -> id to pref.visible }
    }
    val basicEntries = fieldEntries.filter { it.first.isBasic }
    val advancedEntries = fieldEntries.filterNot { it.first.isBasic }
    // Positions in the full saved order, so reordering inside one group moves the right entries and leaves the rest alone.
    val basicPositions = fieldEntries.indices.filter { fieldEntries[it].first.isBasic }
    val advancedPositions = fieldEntries.indices.filterNot { fieldEntries[it].first.isBasic }
    // Anything the user already turned on stays editable: the advanced list starts open when one of its rows is on.
    var showAdvanced by remember { mutableStateOf(advancedEntries.any { it.second }) }
    val basicDragState = rememberDeviceOrderDragState()
    val advancedDragState = rememberDeviceOrderDragState()
    val density = LocalDensity.current
    val itemStridePx = with(density) { 72.dp.toPx() }
    val scrollState = rememberScrollState()

    DeviceOrderEdgeAutoScrollEffect(
        dragState = basicDragState,
        scrollState = scrollState,
        deviceIds = basicEntries.map { it.first.name },
        itemStridePx = itemStridePx,
        viewportHeightPx = 480,
        listOverflowsViewport = basicEntries.size > 6
    )
    DeviceOrderEdgeAutoScrollEffect(
        dragState = advancedDragState,
        scrollState = scrollState,
        deviceIds = advancedEntries.map { it.first.name },
        itemStridePx = itemStridePx,
        viewportHeightPx = 480,
        listOverflowsViewport = advancedEntries.size > 6
    )

    SettingsPageShell(
        title = stringRes("device_details"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(scrollState)
        ) {
            Text(
                text = stringRes("device_details_intro"),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ListItem(
                headlineContent = { Text(stringRes("allow_over_cellular"), softWrap = true) },
                supportingContent = {
                    Text(stringRes("allow_over_cellular_desc"), softWrap = true)
                },
                trailingContent = {
                    Switch(
                        checked = allowOverCellular,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onBeforeAllowOverCellularEnabled {
                                    onAllowOverCellularChange(true)
                                }
                            } else {
                                onAllowOverCellularChange(false)
                            }
                        }
                    )
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            DeviceDetailsFieldRows(basicEntries, basicPositions, basicDragState, itemStridePx, onFieldVisibleChange, onReorderFields)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            ListItem(
                headlineContent = { Text(stringRes("device_details_advanced"), softWrap = true) },
                supportingContent = { Text(stringRes("device_details_advanced_desc"), softWrap = true) },
                trailingContent = { Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it }) }
            )
            if (showAdvanced) {
                DeviceDetailsFieldRows(advancedEntries, advancedPositions, advancedDragState, itemStridePx, onFieldVisibleChange, onReorderFields)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                Text(stringRes("reset_to_defaults"))
            }
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

@Composable
private fun DeviceDetailsFieldRows(
    entries: List<Pair<DeviceDetailsFieldId, Boolean>>,
    positions: List<Int>,
    dragState: DeviceOrderDragState,
    itemStridePx: Float,
    onFieldVisibleChange: (DeviceDetailsFieldId, Boolean) -> Unit,
    onReorderFields: (fromIndex: Int, toIndex: Int) -> Unit
) {
    entries.forEachIndexed { index, (fieldId, visible) ->
        val visualOffsetPx = deviceOrderItemVisualOffsetPx(
            index = index,
            dragState = dragState,
            itemCount = entries.size,
            itemStridePx = itemStridePx
        )
        ListItem(
            modifier = Modifier.offset { IntOffset(0, visualOffsetPx.roundToInt()) },
            headlineContent = { Text(stringRes("field_${fieldId.name}"), softWrap = true) },
            supportingContent = when {
                fieldId.wifiOnly -> {
                    { Text(stringRes("shown_when_wifi"), softWrap = true) }
                }
                fieldId.cellularOnly -> {
                    { Text(stringRes("shown_when_cellular"), softWrap = true) }
                }
                else -> null
            },
            leadingContent = {
                DeviceOrderDragHandle(
                    deviceId = fieldId.name,
                    startIndex = index,
                    itemCount = entries.size,
                    dragState = dragState,
                    itemStridePx = itemStridePx,
                    onReorder = { from, to -> onReorderFields(positions[from], positions[to]) }
                )
            },
            trailingContent = {
                Switch(
                    checked = visible,
                    onCheckedChange = { enabled -> onFieldVisibleChange(fieldId, enabled) }
                )
            }
        )
    }
}
