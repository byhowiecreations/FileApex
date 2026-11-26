package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.TextScaleSettings
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader
import kotlin.math.roundToInt

/** Text size: a percentage on top of the system font size. It applies when the slider is released, so the slider does not move under the finger. */
@Composable
internal fun TextSizeSettingsSection() {
    val scale = FileApexServices.settings.textScale
    val percent by scale.percent.collectAsState()
    var dragging by remember { mutableFloatStateOf(percent.toFloat()) }
    LaunchedEffect(percent) { dragging = percent.toFloat() }
    FileApexPaneSectionHeader(title = stringRes("text_size"))
    Text(
        text = stringRes("text_size_desc"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { scale.setPercent(dragging.roundToInt()) },
            valueRange = TextScaleSettings.MIN.toFloat()..TextScaleSettings.MAX.toFloat(),
            steps = (TextScaleSettings.MAX - TextScaleSettings.MIN) / TextScaleSettings.STEP - 1,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${TextScaleSettings.normalize(dragging.roundToInt())}%",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 12.dp).width(52.dp)
        )
    }
    if (percent != TextScaleSettings.DEFAULT) {
        TextButton(
            onClick = { scale.setPercent(TextScaleSettings.DEFAULT) },
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Text(stringRes("text_size_reset"))
        }
    }
}
