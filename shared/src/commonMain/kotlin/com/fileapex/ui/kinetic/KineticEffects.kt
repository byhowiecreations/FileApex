package com.fileapex.ui.kinetic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.ui.kineticFractionKey
import com.fileapex.ui.kineticStoredNode
import kotlinx.coroutines.delay

/** Saves each device position as a fraction of the canvas once, so a resized window keeps the layout. */
@Composable
internal fun KineticScope.KineticEffects() {
    with(inputs) { with(frame) { with(live) {
        val positionsForBucket by rememberUpdatedState(effectiveNodePositions)
        val widthForBucket by rememberUpdatedState(widthPx)
        val heightForBucket by rememberUpdatedState(heightPx)
        val bucketDeviceIds = deviceRows.joinToString(",") { it.deviceId }
        LaunchedEffect(layoutBucket, bucketDeviceIds) {
            delay(500)
            val canvasW = widthForBucket
            val canvasH = heightForBucket
            if (canvasW < 200f || canvasH < 200f) return@LaunchedEffect
            val current = FileApexServices.settings.kineticNodeOffsets.value
            val marginPx = with(density) { 70.dp.toPx() }
            val topMarginPx = with(density) { 55.dp.toPx() }
            val fractional = mutableMapOf<String, Pair<Float, Float>>()
            deviceRows.forEachIndexed { index, row ->
                val key = kineticFractionKey(layoutBucket, row.deviceId)
                if (current.containsKey(key)) return@forEachIndexed
                val stored = kineticStoredNode(
                    offsets = current,
                    bucket = layoutBucket,
                    deviceId = row.deviceId,
                    widthPx = canvasW,
                    heightPx = canvasH,
                    marginPx = marginPx,
                    topMarginPx = topMarginPx
                )
                if (stored == null || stored.fractional) return@forEachIndexed
                val pos = positionsForBucket.getOrNull(index) ?: return@forEachIndexed
                val savedX = stored.x
                val savedY = stored.y
                if (kotlin.math.abs(pos.x - savedX) > 1.5f || kotlin.math.abs(pos.y - savedY) > 1.5f) {
                    return@forEachIndexed
                }
                fractional[key] = Pair((pos.x / canvasW).coerceIn(0f, 1f), (pos.y / canvasH).coerceIn(0f, 1f))
            }
            FileApexServices.settings.setKineticNodeOffsets(fractional)
        }

    } } }
}
