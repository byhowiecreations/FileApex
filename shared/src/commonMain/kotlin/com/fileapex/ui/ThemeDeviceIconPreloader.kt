package com.fileapex.ui

import androidx.compose.ui.graphics.ImageBitmap
import com.fileapex.data.settings.ThemeIconStyle
import com.fileapex.platform.decodeImageBytes
import fileapex.shared.generated.resources.Res
import fileapex.shared.generated.resources.dev_flux_flip
import fileapex.shared.generated.resources.dev_flux_folding
import fileapex.shared.generated.resources.dev_flux_laptop
import fileapex.shared.generated.resources.dev_flux_slab
import fileapex.shared.generated.resources.dev_flux_tablet
import fileapex.shared.generated.resources.dev_fs_flip8
import fileapex.shared.generated.resources.dev_fs_fold8
import fileapex.shared.generated.resources.dev_fs_generic
import fileapex.shared.generated.resources.dev_fs_honor_magic_v5
import fileapex.shared.generated.resources.dev_fs_honor_x9d
import fileapex.shared.generated.resources.dev_fs_macbook
import fileapex.shared.generated.resources.dev_fs_magic8pro
import fileapex.shared.generated.resources.dev_fs_moto_edge
import fileapex.shared.generated.resources.dev_fs_moto_signature
import fileapex.shared.generated.resources.dev_fs_motorola_razr_fold_2026
import fileapex.shared.generated.resources.dev_fs_oneplus15
import fileapex.shared.generated.resources.dev_fs_oppo_find_x9_pro
import fileapex.shared.generated.resources.dev_fs_pixel_11_pro
import fileapex.shared.generated.resources.dev_fs_pixel_11_pro_fold
import fileapex.shared.generated.resources.dev_fs_poco
import fileapex.shared.generated.resources.dev_fs_windows
import fileapex.shared.generated.resources.opt_flux_battery
import fileapex.shared.generated.resources.opt_flux_clipboard
import fileapex.shared.generated.resources.opt_flux_folder
import fileapex.shared.generated.resources.opt_flux_qrcode
import fileapex.shared.generated.resources.opt_flux_settings
import fileapex.shared.generated.resources.opt_fs_battery
import fileapex.shared.generated.resources.opt_fs_clipboard
import fileapex.shared.generated.resources.opt_fs_folder
import fileapex.shared.generated.resources.opt_fs_qrcode
import fileapex.shared.generated.resources.opt_fs_settings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.getDrawableResourceBytes
import org.jetbrains.compose.resources.getSystemResourceEnvironment

/**
 * Decodes theme device/option PNGs off the UI thread so Kinetic/Freestyle first paint
 * does not stall on `painterResource` (which was leaving Material vector placeholders
 * on screen for many seconds).
 */
object ThemeDeviceIconPreloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = ConcurrentHashMap<DrawableResource, ImageBitmap>()
    private val started = AtomicBoolean(false)
    @Volatile
    private var ready: CompletableDeferred<Unit> = CompletableDeferred()

    fun cached(resource: DrawableResource): ImageBitmap? = cache[resource]

    fun startFor(style: ThemeIconStyle) {
        if (style == ThemeIconStyle.STANDARD) {
            ready.complete(Unit)
            return
        }
        if (!started.compareAndSet(false, true)) return
        val deferred = CompletableDeferred<Unit>()
        ready = deferred
        scope.launch {
            runCatching { preload(style) }
            deferred.complete(Unit)
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 8_000): Boolean {
        if (ready.isCompleted) return true
        return withTimeoutOrNull(timeoutMs) {
            ready.await()
            true
        } == true
    }

    private suspend fun preload(style: ThemeIconStyle) {
        val resources = when (style) {
            ThemeIconStyle.STANDARD -> emptyList()
            ThemeIconStyle.FLUX -> fluxResources
            ThemeIconStyle.FREESTYLE -> freestyleResources
        }
        if (resources.isEmpty()) return
        val environment = getSystemResourceEnvironment()
        coroutineScope {
            resources.map { resource ->
                async(Dispatchers.Default) {
                    if (cache.containsKey(resource)) return@async
                    runCatching {
                        val bytes = getDrawableResourceBytes(environment, resource)
                        val bitmap = decodeImageBytes(bytes) ?: return@async
                        cache.putIfAbsent(resource, bitmap)
                    }
                }
            }.awaitAll()
        }
    }

    private val fluxResources: List<DrawableResource> = listOf(
        Res.drawable.dev_flux_flip,
        Res.drawable.dev_flux_folding,
        Res.drawable.dev_flux_laptop,
        Res.drawable.dev_flux_slab,
        Res.drawable.dev_flux_tablet,
        Res.drawable.opt_flux_battery,
        Res.drawable.opt_flux_clipboard,
        Res.drawable.opt_flux_folder,
        Res.drawable.opt_flux_qrcode,
        Res.drawable.opt_flux_settings,
    )

    private val freestyleResources: List<DrawableResource> = listOf(
        Res.drawable.dev_fs_flip8,
        Res.drawable.dev_fs_fold8,
        Res.drawable.dev_fs_generic,
        Res.drawable.dev_fs_honor_magic_v5,
        Res.drawable.dev_fs_honor_x9d,
        Res.drawable.dev_fs_macbook,
        Res.drawable.dev_fs_magic8pro,
        Res.drawable.dev_fs_moto_edge,
        Res.drawable.dev_fs_moto_signature,
        Res.drawable.dev_fs_motorola_razr_fold_2026,
        Res.drawable.dev_fs_oneplus15,
        Res.drawable.dev_fs_oppo_find_x9_pro,
        Res.drawable.dev_fs_pixel_11_pro,
        Res.drawable.dev_fs_pixel_11_pro_fold,
        Res.drawable.dev_fs_poco,
        Res.drawable.dev_fs_windows,
        Res.drawable.opt_fs_battery,
        Res.drawable.opt_fs_clipboard,
        Res.drawable.opt_fs_folder,
        Res.drawable.opt_fs_qrcode,
        Res.drawable.opt_fs_settings,
    )
}
