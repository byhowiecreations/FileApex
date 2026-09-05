package com.fileapex.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.settings.AppTheme
import com.fileapex.data.settings.BulletinBoardStyle
import com.fileapex.data.settings.LocalAppTheme
import com.fileapex.ui.theme.FileApexTeal
import kotlin.math.sin

// Core Logo Palette
val BrandCoolBlue = Color(0xFF2A75D3)
val BrandCoolBlueDark = Color(0xFF1B4E8F)
val BrandCoolBlueGlow = Color(0xFF56CBDC)

// iOS Modern cool-blue apex gradient derived from the app logo icon
val IosModernCyanTop = Color(0xFF5CD8F2)
val IosModernApexBlue = Color(0xFF3FB5D8) // User-specified logo-derived middle cyan/blue swatch
val IosModernBlueBottom = Color(0xFF208BB0)

// Aero Glass frosted pane styling
val AeroGlassPaneBg = Color(0x661C2630)

// Torn Ledger & Sticky Note styling
val TornLedgerGridStroke = Color(0x383B5998)
val StickyNoteYellow = Color(0xFFFFF79A)
val StickyNoteYellowMine = Color(0xFFFFEE6B)
val TornPaperOffWhite = Color(0xFFFAF7F0)
val TornPaperMine = Color(0xFFF3EFE6)

/**
 * Organic fibrous torn paper shape for Torn Ledger style.
 * Simulates genuine hand-torn paper with natural jagged micro-fibers.
 */
fun tornLedgerShape(): Shape {
    return GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        moveTo(0f, 4f)

        // Organic fibrous torn top edge
        var x = 0f
        while (x < w) {
            val step = 3f + ((x * 7f + 13f) % 4f)
            val nextX = (x + step).coerceAtMost(w)
            val fiber = (((x.toInt() * 37 + 19) % 7) - 3) * 0.65f
            val wave = sin(x * 0.035).toFloat() * 1.5f +
                sin(x * 0.11 + 0.8).toFloat() * 0.9f
            val rip = if ((x.toInt() / 28) % 4 == 1) 1.8f else 0f
            val y = (3.5f + wave + fiber + rip).coerceIn(0.5f, 7.5f)
            lineTo(nextX, y)
            x = nextX
        }

        lineTo(w, h - 4f)

        // Organic fibrous torn bottom edge (right to left)
        x = w
        while (x > 0f) {
            val step = 3f + ((x * 11f + 7f) % 4f)
            val prevX = (x - step).coerceAtLeast(0f)
            val fiber = (((x.toInt() * 43 + 29) % 7) - 3) * 0.65f
            val wave = sin(x * 0.03 + 1.7).toFloat() * 1.5f +
                sin(x * 0.09 + 2.3).toFloat() * 0.9f
            val rip = if ((x.toInt() / 32) % 4 == 2) 1.8f else 0f
            val y = (h - 3.5f - wave - fiber - rip).coerceIn(h - 7.5f, h - 0.5f)
            lineTo(prevX, y)
            x = prevX
        }

        lineTo(0f, 4f)
        close()
    }
}

/**
 * True iOS/macOS chat bubble shape: pill-shaped container with high curvature (18.dp radius)
 * featuring an anchored tail point on the leading edge (bottom-left for incoming)
 * and trailing edge (bottom-right for outgoing messages).
 */
fun iosBubbleShape(isMine: Boolean): Shape {
    return GenericShape { size, _ ->
        val w = size.width
        val h = size.height
        val r = 18f * 2.2f // 18dp high curvature radius (~40px)
        val tw = 11f * 2.2f // tail width (~24px)
        val th = 14f * 2.2f // tail height (~31px)

        if (isMine) {
            // Outgoing: anchored tail on trailing edge (bottom-right)
            val corner = r.coerceAtMost(h * 0.45f).coerceAtMost((w - tw) * 0.45f)
            val tailW = tw.coerceAtMost(w * 0.15f)
            val tailH = th.coerceAtMost(h * 0.45f)

            // Top-left corner
            moveTo(corner, 0f)
            // Top edge to top-right
            lineTo(w - tailW - corner, 0f)
            quadraticTo(w - tailW, 0f, w - tailW, corner)
            // Right edge down to tail start
            lineTo(w - tailW, h - tailH)
            // Outer curve flaring down and out to trailing tail tip at (w, h)
            cubicTo(
                w - tailW * 0.9f, h - tailH * 0.35f,
                w - tailW * 0.25f, h - 0.5f,
                w, h
            )
            // Inner concave curve swooping smoothly back into bottom edge
            cubicTo(
                w - tailW * 0.7f, h - 1.5f,
                w - tailW - 4f, h - 2f,
                w - tailW - corner, h - 2f
            )
            // Bottom edge to bottom-left corner
            lineTo(corner, h - 2f)
            quadraticTo(0f, h - 2f, 0f, h - 2f - corner)
            // Left edge to top-left
            lineTo(0f, corner)
            quadraticTo(0f, 0f, corner, 0f)
        } else {
            // Incoming: anchored tail on leading edge (bottom-left)
            val corner = r.coerceAtMost(h * 0.45f).coerceAtMost((w - tw) * 0.45f)
            val tailW = tw.coerceAtMost(w * 0.15f)
            val tailH = th.coerceAtMost(h * 0.45f)

            // Top-left corner
            moveTo(tailW + corner, 0f)
            // Top edge to top-right
            lineTo(w - corner, 0f)
            quadraticTo(w, 0f, w, corner)
            // Right edge down to bottom-right corner
            lineTo(w, h - 2f - corner)
            quadraticTo(w, h - 2f, w - corner, h - 2f)
            // Bottom edge to tail start
            lineTo(tailW + corner, h - 2f)
            // Inner concave curve swooping smoothly to leading tail tip at (0, h)
            cubicTo(
                tailW + 4f, h - 2f,
                tailW * 0.7f, h - 1.5f,
                0f, h
            )
            // Outer curve flaring up along leading edge from tail tip
            cubicTo(
                tailW * 0.25f, h - 0.5f,
                tailW * 0.9f, h - tailH * 0.35f,
                tailW, h - tailH
            )
            // Left edge up to top-left corner
            lineTo(tailW, corner)
            quadraticTo(tailW, 0f, tailW + corner, 0f)
        }
        close()
    }
}

/**
 * 3D pushpin graphic component rendered when a message is pinned.
 */
@Composable
fun PushPin(
    modifier: Modifier = Modifier,
    pinColor: Color = Color(0xFFD32F2F)
) {
    Canvas(
        modifier = modifier.size(width = 16.dp, height = 22.dp)
    ) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // 1. Drop shadow cast onto paper below pin
        drawOval(
            color = Color(0x50000000),
            topLeft = Offset(cx - 5.dp.toPx(), h - 5.dp.toPx()),
            size = Size(10.dp.toPx(), 4.dp.toPx())
        )

        // 2. Metallic pin needle entering paper
        val needlePath = Path().apply {
            moveTo(cx - 1.dp.toPx(), h - 8.dp.toPx())
            lineTo(cx + 1.dp.toPx(), h - 8.dp.toPx())
            lineTo(cx, h - 1.dp.toPx())
            close()
        }
        drawPath(needlePath, Color(0xFF9E9E9E))

        // 3. Base collar disc
        drawOval(
            brush = Brush.verticalGradient(
                listOf(
                    pinColor,
                    pinColor.copy(red = pinColor.red * 0.65f, green = pinColor.green * 0.65f, blue = pinColor.blue * 0.65f)
                )
            ),
            topLeft = Offset(cx - 4.5.dp.toPx(), h - 9.dp.toPx()),
            size = Size(9.dp.toPx(), 4.5.dp.toPx())
        )

        // 4. Pin body waist with cylindrical specular highlight
        val bodyPath = Path().apply {
            moveTo(cx - 3.5.dp.toPx(), h - 7.5.dp.toPx())
            lineTo(cx - 2.5.dp.toPx(), 7.dp.toPx())
            lineTo(cx + 2.5.dp.toPx(), 7.dp.toPx())
            lineTo(cx + 3.5.dp.toPx(), h - 7.5.dp.toPx())
            close()
        }
        drawPath(
            bodyPath,
            brush = Brush.horizontalGradient(
                listOf(
                    pinColor.copy(alpha = 0.85f),
                    Color.White.copy(alpha = 0.35f),
                    pinColor
                )
            )
        )

        // 5. Spherical bulbous head with radial specular highlight reflection
        val headRadius = 4.5.dp.toPx()
        val headCenter = Offset(cx, 6.dp.toPx())
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.85f),
                    pinColor,
                    pinColor.copy(red = pinColor.red * 0.6f, green = pinColor.green * 0.6f, blue = pinColor.blue * 0.6f)
                ),
                center = Offset(cx - 1.5.dp.toPx(), 4.5.dp.toPx()),
                radius = headRadius
            ),
            radius = headRadius,
            center = headCenter
        )
    }
}

/**
 * Universal container for Bulletin Board message bubbles conforming to the selected style.
 */
@Composable
fun BulletinBubbleContainer(
    style: BulletinBoardStyle,
    isMine: Boolean,
    cardBg: Color,
    isCustomGlass: Boolean,
    highlighted: Boolean,
    isPinned: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        when (style) {
            BulletinBoardStyle.DEFAULT -> {
                val shape = if (isMine) {
                    RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
                } else {
                    RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp)
                }
                val bg = if (isMine) {
                    if (isCustomGlass) Color(0x4400E676) else FileApexTeal.copy(alpha = 0.20f)
                } else {
                    if (isCustomGlass) Color(0xFF15222A) else cardBg
                }

                val borderColor = when {
                    highlighted -> if (isCustomGlass) Color(0xFF00E5FF) else FileApexTeal
                    isCustomGlass -> Color.White.copy(alpha = 0.18f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
                val borderWidth = if (highlighted) 2.dp else 1.dp

                Box(
                    modifier = Modifier
                        .shadow(elevation = 2.dp, shape = shape)
                        .clip(shape)
                        .background(bg)
                        .drawBehind {
                            val strokePx = borderWidth.toPx()
                            val inset = strokePx / 2f
                            drawRoundRect(
                                color = borderColor,
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - strokePx, size.height - strokePx),
                                cornerRadius = CornerRadius(16.dp.toPx() - inset),
                                style = Stroke(width = strokePx)
                            )
                        }
                ) {
                    content()
                }
            }

            BulletinBoardStyle.IOS_MODERN -> {
                val shape = iosBubbleShape(isMine)
                val bgBrush = if (isMine) {
                    // Smooth cool-blue gradient derived from logo apex center swatch (#3FB5D8)
                    Brush.verticalGradient(listOf(IosModernCyanTop, IosModernApexBlue, IosModernBlueBottom))
                } else {
                    Brush.verticalGradient(
                        if (isCustomGlass) listOf(Color(0xFF243038), Color(0xFF1B242A))
                        else listOf(Color(0xFF323236), Color(0xFF242427))
                    )
                }

                Box(
                    modifier = Modifier
                        .shadow(elevation = 2.dp, shape = shape)
                        .clip(shape)
                        .background(bgBrush)
                        .drawBehind {
                            // Inner subtle specular highlight along top edge
                            drawLine(
                                color = Color.White.copy(alpha = if (isMine) 0.35f else 0.12f),
                                start = Offset(14.dp.toPx(), 1f),
                                end = Offset(size.width - 14.dp.toPx(), 1f),
                                strokeWidth = 1.dp.toPx()
                            )
                        }
                ) {
                    Box(
                        modifier = Modifier.padding(
                            start = if (!isMine) 8.dp else 0.dp,
                            end = if (isMine) 8.dp else 0.dp
                        )
                    ) {
                        content()
                    }
                }
            }

            BulletinBoardStyle.MATERIAL_YOU -> {
                val shape = if (isMine) {
                    RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
                } else {
                    RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                }
                val bg = if (isMine) {
                    BrandCoolBlue.copy(alpha = 0.22f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                }

                Box(
                    modifier = Modifier
                        .shadow(1.dp, shape)
                        .clip(shape)
                        .background(bg)
                        .drawBehind {
                            if (highlighted) {
                                drawRoundRect(
                                    color = BrandCoolBlue,
                                    cornerRadius = CornerRadius(20.dp.toPx()),
                                    style = Stroke(2.dp.toPx())
                                )
                            }
                        }
                ) {
                    content()
                }
            }

            BulletinBoardStyle.AERO_GLASS -> {
                val shape = RoundedCornerShape(18.dp)
                val glassBrush = Brush.verticalGradient(
                    listOf(
                        Color(0x40FFFFFF),
                        Color(0x33A0B2C5),
                        Color(0x24A0B2C5)
                    )
                )

                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 6.dp,
                            shape = shape,
                            ambientColor = Color.Black.copy(alpha = 0.5f),
                            spotColor = Color.Black.copy(alpha = 0.5f)
                        )
                        .clip(shape)
                        .background(glassBrush)
                        .drawBehind {
                            val strokePx = 1.dp.toPx()
                            val inset = strokePx / 2f
                            val rPx = 18.dp.toPx() - inset

                            // 1. Soft translucent frosted glass border around the entire pill
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.28f),
                                topLeft = Offset(inset, inset),
                                size = Size(size.width - strokePx, size.height - strokePx),
                                cornerRadius = CornerRadius(rPx),
                                style = Stroke(width = strokePx)
                            )

                            // 2. Clean 1px top specular white highlight border with alpha = 0.5f
                            drawLine(
                                color = Color.White.copy(alpha = 0.5f),
                                start = Offset(14.dp.toPx(), 1f),
                                end = Offset(size.width - 14.dp.toPx(), 1f),
                                strokeWidth = 1.dp.toPx()
                            )

                            // 3. Diffuse horizontal specular sheen along the upper rim
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        Color.White.copy(alpha = 0.70f),
                                        Color.Transparent
                                    )
                                ),
                                start = Offset(size.width * 0.15f, 1.5f),
                                end = Offset(size.width * 0.85f, 1.5f),
                                strokeWidth = 1.5.dp.toPx()
                            )

                            // 4. Subtle frosted top gradient wash to simulate glass refraction
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.18f),
                                        Color.Transparent
                                    ),
                                    startY = 1f,
                                    endY = 16.dp.toPx()
                                ),
                                topLeft = Offset(10.dp.toPx(), 1f),
                                size = Size(size.width - 20.dp.toPx(), 15.dp.toPx())
                            )
                        }
                ) {
                    content()
                }
            }

            BulletinBoardStyle.TORN_LEDGER -> {
                val shape = tornLedgerShape()
                val paperBg = if (isMine) TornPaperMine else TornPaperOffWhite

                Box(
                    modifier = Modifier
                        .shadow(elevation = 2.dp, shape = shape)
                        .clip(shape)
                        .background(paperBg)
                        .drawBehind {
                            // Very clear visible horizontal ruled ledger lines
                            val lineGap = 19.dp.toPx()
                            var y = 18.dp.toPx()
                            val lineColor = TornLedgerGridStroke
                            while (y < size.height - 8.dp.toPx()) {
                                drawLine(
                                    color = lineColor,
                                    start = Offset(4.dp.toPx(), y),
                                    end = Offset(size.width - 4.dp.toPx(), y),
                                    strokeWidth = 1.dp.toPx()
                                )
                                y += lineGap
                            }
                            // Vertical ledger margin and currency column lines
                            val marginX = 26.dp.toPx()
                            drawLine(
                                color = Color(0x38D32F2F),
                                start = Offset(marginX, 3.dp.toPx()),
                                end = Offset(marginX, size.height - 3.dp.toPx()),
                                strokeWidth = 1.dp.toPx()
                            )
                            val rightColX = (size.width - 30.dp.toPx()).coerceAtLeast(marginX + 24.dp.toPx())
                            if (size.width > 130.dp.toPx()) {
                                drawLine(
                                    color = Color(0x283B5998),
                                    start = Offset(rightColX, 3.dp.toPx()),
                                    end = Offset(rightColX, size.height - 3.dp.toPx()),
                                    strokeWidth = 1.dp.toPx()
                                )
                            }
                        }
                ) {
                    content()
                }
            }

            BulletinBoardStyle.STICKY_NOTE -> {
                val shape = RoundedCornerShape(3.dp, 3.dp, 12.dp, 3.dp)
                val noteBg = if (isMine) StickyNoteYellowMine else StickyNoteYellow

                Box(
                    modifier = Modifier
                        .shadow(elevation = 3.dp, shape = shape)
                        .clip(shape)
                        .background(noteBg)
                        .drawBehind {
                            // Soft curved shadow underneath bottom-right corner curl
                            val curlSize = 18.dp.toPx()
                            val curlPath = Path().apply {
                                moveTo(size.width - curlSize, size.height)
                                quadraticTo(
                                    size.width - curlSize * 0.4f, size.height - curlSize * 0.4f,
                                    size.width, size.height - curlSize
                                )
                                lineTo(size.width, size.height)
                                close()
                            }
                            drawPath(curlPath, Color(0x30000000))

                            // Curled ear flap
                            val earPath = Path().apply {
                                moveTo(size.width - curlSize, size.height)
                                quadraticTo(
                                    size.width - curlSize * 0.3f, size.height - curlSize * 0.7f,
                                    size.width, size.height - curlSize
                                )
                                lineTo(size.width - curlSize, size.height - curlSize)
                                close()
                            }
                            drawPath(earPath, Color(0x18FFFFFF))
                        }
                ) {
                    content()
                }
            }
        }

        // Pushpin element rendered strictly when the message is pinned
        if (isPinned && (style == BulletinBoardStyle.STICKY_NOTE || style == BulletinBoardStyle.TORN_LEDGER)) {
            val pinColor = if (style == BulletinBoardStyle.TORN_LEDGER) Color(0xFF2E7D32) else Color(0xFFD32F2F)
            PushPin(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-7).dp),
                pinColor = pinColor
            )
        }
    }
}

/**
 * Preview card rendered inside the Settings Bulletin Board Styles page.
 * Shows an authentic representation of each individual style.
 */
@Composable
fun BulletinStylePreviewCard(
    style: BulletinBoardStyle,
    compact: Boolean = true,
    modifier: Modifier = Modifier
) {
    val currentTheme = LocalAppTheme.current
    val isCustomGlass = currentTheme == AppTheme.FLUX_GLASS ||
        currentTheme == AppTheme.KINETIC_SPHERE
    val isPinned = style == BulletinBoardStyle.STICKY_NOTE || style == BulletinBoardStyle.TORN_LEDGER

    Box(
        modifier = modifier
            .padding(vertical = if (compact) 2.dp else 4.dp),
        contentAlignment = Alignment.CenterEnd
    ) {
        BulletinBubbleContainer(
            style = style,
            isMine = true,
            cardBg = Color(0xFF1E293B),
            isCustomGlass = isCustomGlass,
            highlighted = false,
            isPinned = isPinned,
            modifier = Modifier.padding(top = if (isPinned) 6.dp else 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (compact) 10.dp else 14.dp,
                    vertical = if (compact) 5.dp else 8.dp
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "FileApex",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (compact) 10.sp else 11.sp
                        ),
                        color = when (style) {
                            BulletinBoardStyle.DEFAULT -> if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
                            BulletinBoardStyle.STICKY_NOTE -> Color(0xFFB71C1C)
                            BulletinBoardStyle.TORN_LEDGER -> Color(0xFF1E5BB0)
                            BulletinBoardStyle.IOS_MODERN -> Color.White
                            BulletinBoardStyle.MATERIAL_YOU -> BrandCoolBlue
                            BulletinBoardStyle.AERO_GLASS -> Color(0xFF4ADE80)
                        }
                    )
                    if (isPinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Pinned",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = if (compact) 8.sp else 9.sp),
                            color = when (style) {
                                BulletinBoardStyle.STICKY_NOTE, BulletinBoardStyle.TORN_LEDGER -> Color(0xFF555960)
                                else -> Color.White.copy(alpha = 0.7f)
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = if (compact) "Sample note" else when (style) {
                        BulletinBoardStyle.DEFAULT -> "Classic FileApex chat bubbles"
                        BulletinBoardStyle.IOS_MODERN -> "Cool-blue apex gradient with anchored tail"
                        BulletinBoardStyle.MATERIAL_YOU -> "Playful asymmetrical pill contours"
                        BulletinBoardStyle.AERO_GLASS -> "Translucent frosted glass pane with top specular highlight"
                        BulletinBoardStyle.TORN_LEDGER -> "Ruled ledger paper with fibrous torn edges"
                        BulletinBoardStyle.STICKY_NOTE -> "Canary post-it note with curled corner"
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = if (compact) 11.sp else 12.sp),
                    color = when (style) {
                        BulletinBoardStyle.STICKY_NOTE, BulletinBoardStyle.TORN_LEDGER -> Color(0xFF1E2022)
                        BulletinBoardStyle.IOS_MODERN -> Color.White
                        else -> Color.White.copy(alpha = 0.9f)
                    }
                )
            }
        }
    }
}
