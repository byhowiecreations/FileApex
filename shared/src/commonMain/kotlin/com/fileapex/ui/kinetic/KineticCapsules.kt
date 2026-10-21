package com.fileapex.ui.kinetic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.i18n.stringRes
import com.fileapex.ui.BatteryIcon
import com.fileapex.ui.devices.SendClipboardActionChip
import com.fileapex.ui.theme.KineticStyleLook
import kotlin.math.roundToInt

@Composable
internal fun KineticScope.KineticClipboardChip() {
    with(inputs) { with(frame) { with(live) {
        if (onSendClipboard != null) {
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            centerX.roundToInt(),
                            (centerY - hubPx / 2f - 16.dp.toPx()).roundToInt()
                        )
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-placeable.width / 2, -placeable.height)
                        }
                    }
            ) {
                SendClipboardActionChip(onClick = onSendClipboard)
            }
        }

    } } }
}

@Composable
internal fun KineticScope.KineticBatteryChip() {
    with(inputs) { with(frame) { with(live) {
        if (deviceRows.isNotEmpty() && onCheckBatteries != null) {
            val batteryText = if (deviceRows.size <= 1) {
                stringRes("check_battery")
            } else {
                stringRes("check_batteries")
            }
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            centerX.roundToInt(),
                            (centerY + hubPx / 2f + 16.dp.toPx()).roundToInt()
                        )
                    }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, placeable.height) {
                            placeable.placeRelative(-placeable.width / 2, 0)
                        }
                    }
            ) {
                val batteryShape = RoundedCornerShape(16.dp)
                Surface(
                    onClick = onCheckBatteries,
                    shape = batteryShape,
                    color = when (kineticStyle) {
                        KineticStyle.JADED_STEEL -> KineticStyleLook.jadedCard
                        KineticStyle.SPACE -> Color(0xDD0D1C22)
                    },
                    border = when (kineticStyle) {
                        KineticStyle.JADED_STEEL -> BorderStroke(1.dp, KineticStyleLook.jadedCardEdge)
                        KineticStyle.SPACE -> BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.50f))
                    },
                    shadowElevation = if (kineticStyle == KineticStyle.JADED_STEEL) 0.dp else 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        BatteryIcon(
                            modifier = Modifier.size(15.dp),
                            tint = Color(0xFF00E676)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = batteryText,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.5.sp
                            ),
                            color = Color.White,
                            softWrap = true,
                            maxLines = 2
                        )
                    }
                }
            }
        }

    } } }
}
