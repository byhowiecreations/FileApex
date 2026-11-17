package com.fileapex.ui.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.fileapex.data.settings.ReducedMotionSetting
import com.fileapex.di.FileApexServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

val LocalReducedMotion = staticCompositionLocalOf { false }

object ReducedMotionPolicy {
    fun resolve(setting: ReducedMotionSetting, systemReduced: Boolean): Boolean = when (setting) {
        ReducedMotionSetting.On -> true
        ReducedMotionSetting.Off -> false
        ReducedMotionSetting.System -> systemReduced
    }
}

/** The operating system's own reduced-motion choice, read once when the app starts and again when it is asked to. */
object SystemReducedMotion {
    private val state = MutableStateFlow(false)
    val value: StateFlow<Boolean> = state.asStateFlow()

    suspend fun refresh() {
        state.value = withContext(Dispatchers.IO) { systemReducedMotion() }
    }

    /** The current answer for code that is not in a composition (the desktop window). */
    fun isReduced(): Boolean = ReducedMotionPolicy.resolve(FileApexServices.settings.motion.reduceMotion.value, state.value)
}

/** The operating system's reduced-motion switch: Android animator scale, macOS, Windows and GNOME settings. */
expect fun systemReducedMotion(): Boolean

@Composable
fun ProvideReducedMotion(content: @Composable () -> Unit) {
    val setting by FileApexServices.settings.motion.reduceMotion.collectAsState()
    val system by SystemReducedMotion.value.collectAsState()
    LaunchedEffect(Unit) { SystemReducedMotion.refresh() }
    CompositionLocalProvider(LocalReducedMotion provides ReducedMotionPolicy.resolve(setting, system), content = content)
}
