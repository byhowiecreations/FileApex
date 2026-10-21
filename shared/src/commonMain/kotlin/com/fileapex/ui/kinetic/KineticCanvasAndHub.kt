package com.fileapex.ui.kinetic

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.KineticStyle
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.KineticStyleLook
import kotlin.math.roundToInt

@Composable
internal fun KineticScope.KineticOrbitCanvas() {
    with(inputs) { with(frame) { with(live) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stars = listOf(
                Offset(centerX * 0.3f, centerY * 0.4f),
                Offset(centerX * 1.6f, centerY * 0.3f),
                Offset(centerX * 0.2f, centerY * 1.5f),
                Offset(centerX * 1.7f, centerY * 1.6f),
                Offset(centerX * 1.4f, centerY * 0.8f),
                Offset(centerX * 0.6f, centerY * 1.2f)
            )
            if (kineticStyle == KineticStyle.SPACE) {
                stars.forEach { star ->
                    drawCircle(color = Color.White.copy(alpha = 0.4f), radius = 2f, center = star)
                }
            }

            if (showConnectedLines) {
                effectiveNodePositions.forEachIndexed { index, pos ->
                    val row = deviceRows.getOrNull(index) ?: return@forEachIndexed
                    val online = row.online
                    val statusGlow = if (online) Color(0xFF00E676) else Color(0xFFFFC107)
                    val start = Offset(centerX, centerY)
                        val lineColor = if (kineticStyle == KineticStyle.JADED_STEEL) {
                            statusGlow
                        } else {
                            statusGlow.copy(alpha = 0.50f)
                        }
                        val lineWidth = if (kineticStyle == KineticStyle.JADED_STEEL) 1f else 1.5f.dp.toPx()
                        drawLine(
                            color = lineColor,
                            start = start,
                            end = pos,
                            strokeWidth = lineWidth,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                }
            }
        }

    } } }
}

@Composable
internal fun KineticScope.KineticHub() {
    with(inputs) { with(frame) { with(live) {
        Box(
            modifier = Modifier.offset {
                IntOffset((centerX - hubPx / 2f).roundToInt(), (centerY - hubPx / 2f).roundToInt())
            }
        ) {
            Box(
                modifier = Modifier
                    .size(hubSizeDp)
                    .then(
                            Modifier
                                .clip(CircleShape)
                                .then(
                                    when (kineticStyle) {
                                        KineticStyle.JADED_STEEL -> Modifier.background(KineticStyleLook.jadedCard)
                                        KineticStyle.SPACE -> Modifier.background(
                                            Brush.radialGradient(
                                                colors = listOf(
                                                    Color(0x9900E5FF),
                                                    Color(0x660A2A4A),
                                                    Color(0x22030B14)
                                                )
                                            )
                                        )
                                    }
                                )
                                .border(
                                    when (kineticStyle) {
                                        KineticStyle.JADED_STEEL -> BorderStroke(
                                            1.dp,
                                            KineticStyleLook.jadedCardEdge
                                        )
                                        KineticStyle.SPACE -> BorderStroke(2.dp, Color(0xFF00E5FF).copy(alpha = 0.85f))
                                    },
                                    CircleShape
                                )
                    )
                    .clickable { addMenuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringRes("add_new_device"),
                        tint = when (kineticStyle) {
                            KineticStyle.JADED_STEEL -> KineticStyleLook.jade
                            KineticStyle.SPACE -> Color(0xFF00E5FF)
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringRes("add_new_device"),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            lineHeight = 13.sp
                        ),
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        softWrap = true,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .width(94.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = addMenuOpen,
                onDismissRequest = { addMenuOpen = false }
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.QrCode,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = stringRes("generate_qr"), color = Color.White)
                        }
                    },
                    onClick = {
                        addMenuOpen = false
                        onGenerateQr()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.QrCodeScanner,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(text = stringRes("join_device"), color = Color.White)
                        }
                    },
                    onClick = {
                        addMenuOpen = false
                        onJoinDevice()
                    }
                )
            }
        }

    } } }
}
