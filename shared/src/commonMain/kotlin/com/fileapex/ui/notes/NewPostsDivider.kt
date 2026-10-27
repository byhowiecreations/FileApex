package com.fileapex.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fileapex.i18n.stringRes
import com.fileapex.ui.theme.FileApexTeal

@Composable
internal fun NewPostsDivider(isCustomGlass: Boolean) {
    val accent = if (isCustomGlass) Color(0xFF00E676) else FileApexTeal
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.6f)))
        Text(
            text = stringRes("bulletin_new_divider"),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = accent
        )
        Box(modifier = Modifier.weight(1f).height(1.dp).background(accent.copy(alpha = 0.6f)))
    }
}
