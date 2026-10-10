package com.fileapex.ui.adaptive

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.domain.device.PhoneLocator
import com.fileapex.i18n.stringRes

/** Floats above the Overview while a phone is being rung, with an X that silences it. */
@Composable
internal fun SimpleLocateCard(modifier: Modifier = Modifier) {
    val ringing by PhoneLocator.current.collectAsState()
    val phone = ringing ?: return
    val shape = RoundedCornerShape(16.dp)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    // The stacked shadow and a bright top edge lift the card off the page.
    Surface(
        modifier = modifier
            .shadow(elevation = 28.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
            .widthIn(max = 320.dp),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 8.dp,
        border = BorderStroke(1.dp, highlight)
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.padding(end = 4.dp)) {
                Text(stringRes("locate_phone_pinging"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    phone.deviceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = PhoneLocator::cancel) {
                Icon(Icons.Filled.Close, contentDescription = stringRes("locate_phone_cancel"), modifier = Modifier.size(20.dp))
            }
        }
    }
}
