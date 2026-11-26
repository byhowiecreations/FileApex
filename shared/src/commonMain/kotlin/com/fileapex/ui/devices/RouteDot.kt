package com.fileapex.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fileapex.presentation.DeviceListRow
import com.fileapex.presentation.RouteLight

private val RouteGreen = Color(0xFF34D399)
private val RouteYellow = Color(0xFFFBBF24)
private val RouteRed = Color(0xFFF87171)
private val BadgeRing = Color(0xF20B1216)
private val BehindVersion = Color(0xFFF59E0B)

internal val RouteBadgeSize = 14.dp

private fun RouteLight.color(): Color = when (this) {
    RouteLight.Green -> RouteGreen
    RouteLight.Yellow -> RouteYellow
    RouteLight.Red -> RouteRed
}

/** How far in from the corner a [badge] sits to touch the inside edge of a round icon of [orbSize] on its lower-right diagonal. */
internal fun orbBadgeInset(orbSize: Dp, badge: Dp = RouteBadgeSize): Dp = (orbSize - badge) * 0.1464f

/**
 * The route traffic light, drawn on the lower-right of a device icon: a dark ring keeps it visible on any icon,
 * and the route name is its screen reader label, so colour is never the only signal.
 */
@Composable
internal fun RouteBadge(row: DeviceListRow, modifier: Modifier = Modifier) {
    val label = if (row.online) DeviceListRow.localizedOnlineLabel(row.reachedBy) else DeviceListRow.offlineLabel(row.canWake)
    Box(
        modifier = modifier
            .size(RouteBadgeSize)
            .clip(CircleShape)
            .background(BadgeRing)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(RouteBadgeSize - 4.dp).clip(CircleShape).background(row.routeLight.color()))
    }
}

/** The text a card prints under the name: the version only when behind (amber), "Offline" when offline, otherwise nothing. */
@Composable
internal fun DeviceCaption(
    row: DeviceListRow,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Start,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    val caption = row.cardCaption ?: return
    Text(
        text = caption,
        style = style,
        color = if (row.online && row.isBehind) BehindVersion else color,
        textAlign = textAlign,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onTextLayout = onTextLayout
    )
}
