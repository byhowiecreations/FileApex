package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fileapex.data.settings.ReducedMotionSetting
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.stringRes
import com.fileapex.ui.adaptive.FileApexPaneSectionHeader

@Composable
private fun label(setting: ReducedMotionSetting): String = when (setting) {
    ReducedMotionSetting.System -> stringRes("reduce_motion_system")
    ReducedMotionSetting.On -> stringRes("reduce_motion_on")
    ReducedMotionSetting.Off -> stringRes("reduce_motion_off")
}

/** Reduce motion: follow the system, or force it on or off. When on, movement and scale become instant and fades stay short. */
@Composable
internal fun MotionSettingsSection() {
    val motion = FileApexServices.settings.motion
    val current by motion.reduceMotion.collectAsState()
    FileApexPaneSectionHeader(title = stringRes("motion"))
    Text(
        text = stringRes("reduce_motion_desc"),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        KineticSegmentRow(
            label = stringRes("reduce_motion"),
            options = ReducedMotionSetting.entries.map { label(it) to (current == it) },
            onSelect = { index -> motion.setReduceMotion(ReducedMotionSetting.entries[index]) }
        )
    }
}
