package com.fileapex.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.fileapex.di.FileApexServices
import com.fileapex.i18n.AppI18n
import com.fileapex.platform.BriefToast
import com.fileapex.platform.FileApexAndroidBootstrap
import com.fileapex.util.cancellableCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Translucent popup over the home screen to post a Bulletin Board note without opening the main app.
 */
class BulletinQuickPostActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!FileApexAndroidBootstrap.ensureInitialized(this)) {
            BriefToast.show("Unlock device storage first")
            finish()
            return
        }
        setContent {
            FileApexWidgetTheme {
                QuickPostDialogContent(
                    onDismiss = { finish() },
                    onOpenBoard = {
                        AndroidWidgetBridge.onOpenMainActivityDestination(
                            applicationContext,
                            WidgetDeepLinks.MainDestination.BulletinBoard
                        )
                        finish()
                    },
                    onPostNote = { raw ->
                        val trimmed = raw.trim()
                        if (trimmed.isEmpty()) return@QuickPostDialogContent false
                        val result = withContext(Dispatchers.IO) {
                            cancellableCatching {
                                FileApexServices.noteRepository.sendNote(content = trimmed)
                            }
                        }
                        result.fold(
                            onSuccess = {
                                WidgetUiFormatter.updateBulletinWidgets(applicationContext)
                                BriefToast.show(AppI18n.t("posted_to_bulletin"))
                                finish()
                                true
                            },
                            onFailure = { error ->
                                BriefToast.show(error.message ?: AppI18n.t("widget_quick_post_failed"))
                                false
                            }
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun QuickPostDialogContent(
    onDismiss: () -> Unit,
    onOpenBoard: () -> Unit,
    onPostNote: suspend (String) -> Boolean
) {
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val submit: () -> Unit = {
        if (!sending && text.isNotBlank()) {
            sending = true
            scope.launch {
                val closed = onPostNote(text)
                if (!closed) sending = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.48f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
            .imePadding()
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = AppI18n.t("widget_quick_post_title"),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                    placeholder = { Text(AppI18n.t("widget_quick_post_hint")) },
                    enabled = !sending,
                    minLines = 2,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { submit() })
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onOpenBoard, enabled = !sending) {
                        Text(AppI18n.t("widget_quick_post_open_board"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss, enabled = !sending) {
                            Text(AppI18n.t("cancel"))
                        }
                        Button(onClick = submit, enabled = !sending && text.isNotBlank()) {
                            Text(AppI18n.t("widget_quick_post_send"))
                        }
                    }
                }
            }
        }
    }
}
