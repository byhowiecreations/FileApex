package com.fileapex.ui.notes

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fileapex.data.note.NoteChecklistItem
import com.fileapex.i18n.stringRes

/** What a checklist post can do; the screen turns each into a change that is saved and sent to the other devices. */
internal class ChecklistActions(
    val toggle: (itemId: String, done: Boolean) -> Unit,
    val remove: (itemId: String) -> Unit,
    val add: (text: String) -> Unit
)

@Composable
internal fun ChecklistLines(
    lines: List<NoteChecklistItem>,
    actions: ChecklistActions?,
    textColor: Color,
    accent: Color,
    enabled: Boolean
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        if (lines.isNotEmpty()) {
            Text(
                text = stringRes("checklist_progress", lines.count { it.done }, lines.size),
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f)
            )
        }
        for (line in lines) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The default checkbox reserves a 48 dp touch cell, which spaced the lines far apart.
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
                    Checkbox(
                        checked = line.done,
                        onCheckedChange = { actions?.toggle?.invoke(line.id, it) },
                        enabled = enabled && actions != null,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = line.text,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDecoration = if (line.done) TextDecoration.LineThrough else TextDecoration.None
                    ),
                    color = if (line.done) textColor.copy(alpha = 0.55f) else textColor
                )
                if (enabled && actions != null) {
                    IconButton(onClick = { actions.remove(line.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringRes("checklist_remove_item"),
                            tint = textColor.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
        if (enabled && actions != null) AddLineField(actions.add, textColor, accent)
    }
}

@Composable
private fun AddLineField(onAdd: (String) -> Unit, textColor: Color, accent: Color) {
    var text by remember { mutableStateOf("") }
    val submit = {
        val clean = text.trim()
        if (clean.isNotEmpty()) onAdd(clean)
        text = ""
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(16.dp),
            color = Color.Transparent,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = TextStyle(color = textColor, fontSize = 13.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(stringRes("checklist_add_item"), color = textColor.copy(alpha = 0.5f), fontSize = 13.sp)
                        inner()
                    }
                }
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(onClick = submit, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Add, contentDescription = stringRes("checklist_add_item"), tint = accent)
        }
    }
}
