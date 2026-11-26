package com.fileapex.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.fileapex.data.settings.AppTheme

/** The three tones the light themes draw their accent in: fills, strong text and icons, and a pale tint behind selected items. */
data class AccentColors(val main: Color, val dark: Color, val container: Color)

/** Accents a user can pick for the light themes. Every one keeps white text on [AccentColors.main] readable. */
enum class AccentChoice(val storage: String, val colors: AccentColors) {
    Blue("blue", AccentColors(Color(0xFF2459B2), Color(0xFF1A4388), Color(0xFFDCE8F8))),
    Teal("teal", AccentColors(Color(0xFF0F766E), Color(0xFF0A5C56), Color(0xFFDDF1EE))),
    Indigo("indigo", AccentColors(Color(0xFF4338CA), Color(0xFF3730A3), Color(0xFFE2E0FA))),
    Violet("violet", AccentColors(Color(0xFF6D28D9), Color(0xFF5B21B6), Color(0xFFEDE5FB))),
    Rose("rose", AccentColors(Color(0xFFBE123C), Color(0xFF9F1239), Color(0xFFFCE4EA))),
    Orange("orange", AccentColors(Color(0xFFC2410C), Color(0xFF9A3412), Color(0xFFFDE8DA))),
    Green("green", AccentColors(Color(0xFF15803D), Color(0xFF166534), Color(0xFFDDF3E4))),
    Slate("slate", AccentColors(Color(0xFF475569), Color(0xFF334155), Color(0xFFE5E9EF)));

    companion object {
        fun fromStorage(value: String): AccentChoice? = entries.firstOrNull { it.storage == value }

        /** Clean is a neutral light theme and starts blue; the others keep the brand teal. */
        fun defaultFor(theme: AppTheme): AccentChoice = if (theme == AppTheme.CLEAN) Blue else Teal
    }
}

val LocalAccent = staticCompositionLocalOf { AccentChoice.Teal.colors }

@Composable
fun fileApexAccent(): Color = LocalAccent.current.main

@Composable
fun fileApexAccentDark(): Color = LocalAccent.current.dark

@Composable
fun fileApexAccentContainer(): Color = LocalAccent.current.container
