package com.fileapex.data.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-theme icon styles and the theme to show in place of an unavailable Carousel, kept apart from the
 * rest of the settings.
 */
class ThemeStyleStore(private val store: SettingsKvStore) {
    private val flux = MutableStateFlow(read(KEY_FLUX, ThemeIconStyle.FLUX))
    private val kinetic = MutableStateFlow(read(KEY_KINETIC, ThemeIconStyle.STANDARD))
    private val freestyle = MutableStateFlow(read(KEY_FREESTYLE, ThemeIconStyle.FREESTYLE))
    private val carousel = MutableStateFlow(read(KEY_CAROUSEL, ThemeIconStyle.FREESTYLE))
    private val fallback = MutableStateFlow(AppTheme.fromStorage(store.getString(KEY_FALLBACK, AppTheme.DEFAULT.name)))
    private val favorite = MutableStateFlow(store.getString(KEY_FAVORITE, ""))

    /** The device the Carousel centres on launch; blank when none is starred. */
    val favoriteDevice: StateFlow<String> = favorite.asStateFlow()

    fun setFavoriteDevice(deviceId: String) {
        store.putString(KEY_FAVORITE, deviceId)
        favorite.value = deviceId
    }

    /** The theme a stored Carousel gives way to; the one in use before Carousel was chosen. */
    val fallbackTheme: StateFlow<AppTheme> = fallback.asStateFlow()

    private fun read(key: String, default: ThemeIconStyle) = ThemeIconStyle.fromStorage(store.getString(key, default.name))

    fun iconStyleFor(theme: AppTheme): ThemeIconStyle = when (theme) {
        AppTheme.SIMPLE, AppTheme.CLEAN -> ThemeIconStyle.STANDARD
        AppTheme.FLUX_GLASS -> flux.value
        AppTheme.KINETIC_SPHERE -> kinetic.value
        AppTheme.FREESTYLE -> freestyle.value
        AppTheme.CAROUSEL -> carousel.value
    }

    fun setIconStyle(theme: AppTheme, style: ThemeIconStyle) {
        val valid = if (style in theme.supportedIconStyles()) style else theme.defaultIconStyle()
        val target = when (theme) {
            AppTheme.SIMPLE, AppTheme.CLEAN -> return
            AppTheme.FLUX_GLASS -> flux to KEY_FLUX
            AppTheme.KINETIC_SPHERE -> kinetic to KEY_KINETIC
            AppTheme.FREESTYLE -> freestyle to KEY_FREESTYLE
            AppTheme.CAROUSEL -> carousel to KEY_CAROUSEL
        }
        store.putString(target.second, valid.name)
        target.first.value = valid
    }

    /** Called when the user picks [chosen] while [previous] was active. */
    fun themeChosen(previous: AppTheme, chosen: AppTheme) {
        if (chosen != AppTheme.CAROUSEL || previous == AppTheme.CAROUSEL) return
        store.putString(KEY_FALLBACK, previous.name)
        fallback.value = previous
    }

    fun reset() {
        flux.value = ThemeIconStyle.FLUX
        kinetic.value = ThemeIconStyle.STANDARD
        freestyle.value = ThemeIconStyle.FREESTYLE
        carousel.value = ThemeIconStyle.FREESTYLE
        fallback.value = AppTheme.DEFAULT
        favorite.value = ""
    }

    private companion object {
        const val KEY_FLUX = "theme_icon_style_flux"
        const val KEY_KINETIC = "theme_icon_style_kinetic"
        const val KEY_FREESTYLE = "theme_icon_style_freestyle"
        const val KEY_CAROUSEL = "theme_icon_style_carousel"
        const val KEY_FALLBACK = "carousel_fallback_theme"
        const val KEY_FAVORITE = "carousel_favorite_device"
    }
}
