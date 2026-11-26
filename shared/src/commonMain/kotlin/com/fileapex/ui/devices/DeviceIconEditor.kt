package com.fileapex.ui.devices

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.domain.devices.DeviceIconCrop
import com.fileapex.domain.devices.DeviceIconSyncCoordinator
import com.fileapex.i18n.stringRes
import com.fileapex.platform.decodeLocalImageFile
import com.fileapex.platform.rememberDownloadsFilePicker
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SOURCE_MAX_EDGE = 1024
private const val ICON_EDGE = 256

private sealed interface PendingIconSync {
    class Set(val bitmap: ImageBitmap) : PendingIconSync
    data object Remove : PendingIconSync
}

/** The custom icon controls of the Device Details dialog: choose a picture, or go back to the built-in icon. */
@Composable
internal fun DeviceIconSection(deviceId: String, hasCustomIcon: Boolean) {
    var source by remember { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    var pendingSync by remember { mutableStateOf<PendingIconSync?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberDownloadsFilePicker { picked ->
        if (picked == null) return@rememberDownloadsFilePicker
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodeLocalImageFile(picked.absolutePath, SOURCE_MAX_EDGE) }
            failed = bitmap == null
            source = bitmap
        }
    }
    fun askToSyncIfPeersExist(change: PendingIconSync) {
        scope.launch {
            val hasPeers = withContext(Dispatchers.IO) {
                FileApexServices.deviceRepository.listDevices().any { !it.isRemoved && it.deviceId != deviceId }
            }
            if (hasPeers) pendingSync = change
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(stringRes("custom_icon"), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = pick) { Text(stringRes("custom_icon_choose")) }
            if (hasCustomIcon) {
                TextButton(
                    onClick = {
                        FileApexServices.deviceIcons.remove(deviceId)
                        askToSyncIfPeersExist(PendingIconSync.Remove)
                    }
                ) { Text(stringRes("custom_icon_remove")) }
            }
        }
        if (failed) {
            Text(stringRes("custom_icon_failed"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    source?.let { picture ->
        DeviceIconEditor(
            deviceId = deviceId,
            source = picture,
            onDismiss = { source = null },
            onSaved = { saved -> askToSyncIfPeersExist(PendingIconSync.Set(saved)) }
        )
    }
    pendingSync?.let { sync ->
        val isRemove = sync is PendingIconSync.Remove
        AlertDialog(
            onDismissRequest = { pendingSync = null },
            title = { Text(stringRes(if (isRemove) "custom_icon_sync_remove_title" else "custom_icon_sync_title")) },
            text = { Text(stringRes(if (isRemove) "custom_icon_sync_remove_body" else "custom_icon_sync_body")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val bitmap = (sync as? PendingIconSync.Set)?.bitmap
                        pendingSync = null
                        scope.launch {
                            withContext(Dispatchers.IO) { DeviceIconSyncCoordinator.push(deviceId, bitmap) }
                        }
                    }
                ) { Text(stringRes("custom_icon_sync_yes")) }
            },
            dismissButton = {
                TextButton(onClick = { pendingSync = null }) { Text(stringRes("custom_icon_sync_no")) }
            }
        )
    }
}

/**
 * Places a picture inside the round icon: drag to move, slide to zoom. The ring marks the safe area, so artwork inside it
 * never touches the edge of the icon wherever the card is shown. What the preview shows is exactly what is saved.
 */
@Composable
private fun DeviceIconEditor(deviceId: String, source: ImageBitmap, onDismiss: () -> Unit, onSaved: (ImageBitmap) -> Unit) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringRes("custom_icon")) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .clip(CircleShape)
                        .pointerInput(source) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                offset = DeviceIconCrop.clampOffset(
                                    Offset(offset.x + drag.x / size.width, offset.y + drag.y / size.height),
                                    source.width, source.height, zoom
                                )
                            }
                        }
                ) {
                    Canvas(modifier = Modifier.size(200.dp)) {
                        drawRect(color = Color.White.copy(alpha = 0.06f))
                        val place = DeviceIconCrop.placement(source.width, source.height, zoom, offset)
                        drawImage(
                            image = source,
                            dstOffset = IntOffset((place.left * size.width).roundToInt(), (place.top * size.height).roundToInt()),
                            dstSize = IntSize((place.width * size.width).roundToInt(), (place.height * size.height).roundToInt())
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.85f),
                            radius = size.minDimension / 2f * DeviceIconCrop.SAFE_AREA,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(stringRes("custom_icon_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = zoom,
                    onValueChange = {
                        zoom = DeviceIconCrop.clampZoom(it)
                        offset = DeviceIconCrop.clampOffset(offset, source.width, source.height, zoom)
                    },
                    valueRange = DeviceIconCrop.MIN_ZOOM..DeviceIconCrop.MAX_ZOOM,
                    modifier = Modifier.fillMaxWidth()
                )
                if (failed) {
                    Text(stringRes("custom_icon_failed"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        val rendered = renderIcon(source, zoom, offset)
                        val saved = FileApexServices.deviceIcons.save(deviceId, rendered)
                        saving = false
                        failed = !saved
                        if (saved) {
                            onDismiss()
                            onSaved(rendered)
                        }
                    }
                }
            ) { Text(stringRes("save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringRes("cancel")) } }
    )
}

/** The picture as the icon shows it, at the size that is stored; the area around a smaller picture stays transparent. */
private fun renderIcon(source: ImageBitmap, zoom: Float, offset: Offset): ImageBitmap {
    val place = DeviceIconCrop.placement(source.width, source.height, zoom, offset)
    val output = ImageBitmap(ICON_EDGE, ICON_EDGE)
    GraphicsCanvas(output).drawImageRect(
        image = source,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(source.width, source.height),
        dstOffset = IntOffset((place.left * ICON_EDGE).roundToInt(), (place.top * ICON_EDGE).roundToInt()),
        dstSize = IntSize((place.width * ICON_EDGE).roundToInt(), (place.height * ICON_EDGE).roundToInt()),
        paint = Paint().apply { filterQuality = androidx.compose.ui.graphics.FilterQuality.High }
    )
    return output
}
