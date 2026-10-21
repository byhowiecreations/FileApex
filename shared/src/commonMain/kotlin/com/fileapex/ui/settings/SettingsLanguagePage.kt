package com.fileapex.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import com.fileapex.i18n.AppI18n
import com.fileapex.i18n.AppLocale
import com.fileapex.i18n.persistAppLanguage
import com.fileapex.i18n.stringRes
import com.fileapex.ui.SettingsScreenLayoutMode

@Composable
internal fun LanguageSettingsPage(
    layoutMode: SettingsScreenLayoutMode,
    onBack: () -> Unit
) {
    val selected = AppI18n.locale
    SettingsPageShell(
        title = stringRes("language"),
        layoutMode = layoutMode,
        onBack = onBack
    ) { contentModifier ->
        Column(
            modifier = contentModifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            AppLocale.entries.forEach { locale ->
                val label = AppI18n.languageRowLabel(locale)
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = locale == selected,
                            onClick = { persistAppLanguage(locale) },
                            role = Role.RadioButton
                        ),
                    headlineContent = {
                        Text(label, softWrap = true, modifier = Modifier.fillMaxWidth())
                    },
                    trailingContent = {
                        RadioButton(
                            selected = locale == selected,
                            onClick = { persistAppLanguage(locale) }
                        )
                    }
                )
            }
        }
    }
}
