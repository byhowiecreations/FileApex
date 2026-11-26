package com.fileapex.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.AppTheme
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import com.fileapex.ui.theme.AccentChoice

@Composable
private fun name(choice: AccentChoice): String = when (choice) {
    AccentChoice.Blue -> stringRes("accent_blue")
    AccentChoice.Teal -> stringRes("accent_teal")
    AccentChoice.Indigo -> stringRes("accent_indigo")
    AccentChoice.Violet -> stringRes("accent_violet")
    AccentChoice.Rose -> stringRes("accent_rose")
    AccentChoice.Orange -> stringRes("accent_orange")
    AccentChoice.Green -> stringRes("accent_green")
    AccentChoice.Slate -> stringRes("accent_slate")
}

/** The accent for the Clean theme. The other themes draw in their own colours, so the picker is only shown for Clean. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AccentSettingsSection(theme: AppTheme) {
    if (theme != AppTheme.CLEAN) return
    val accent = FileApexServices.settings.accent
    val picked by accent.choice.collectAsState()
    val effective = picked ?: AccentChoice.defaultFor(theme)
    FileApexPaneSectionHeader(title = stringRes("accent_color"))
    Text(
        text = stringRes("accent_color_desc"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        for (choice in AccentChoice.entries) {
            val selected = choice == effective
            val label = name(choice)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(choice.colors.main)
                    .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Black.copy(alpha = 0.15f)), CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { accent.setChoice(choice) })
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
    if (picked != null) {
        TextButton(onClick = { accent.setChoice(null) }, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text(stringRes("accent_default"))
        }
    }
}
