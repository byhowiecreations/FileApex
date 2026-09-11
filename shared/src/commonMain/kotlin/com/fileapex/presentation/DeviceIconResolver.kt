package com.fileapex.presentation

import fileapex.shared.generated.resources.*
import org.jetbrains.compose.resources.DrawableResource

/**
 * Resolved device glyph — shared by list cards, grid cells, and platform shortcuts.
 */
enum class DeviceIconKind {
    MacDesktop,
    WindowsPc,
    FoldablePhone,
    FlipPhone,
    PixelPhone,
    SamsungPhone,
    AndroidTablet,
    AndroidPhone,
    GenericDesktop
}

data class DeviceIconProfile(
    val deviceId: String = "",
    val deviceName: String = "",
    val hardware: DeviceHardwareProfile = DeviceHardwareProfile()
)

/**
 * Maps platform identifiers and hardware strings to a specific device icon kind.
 */
fun resolveDeviceIconKind(profile: DeviceIconProfile): DeviceIconKind {
    val modelName = DeviceModelLookup.lookupMarketingName(profile.hardware.deviceModel) ?: profile.hardware.deviceModel
    val inferredMake = profile.hardware.deviceMake.ifBlank { DeviceModelLookup.inferMake(profile.hardware.deviceModel).orEmpty() }
    val haystack = buildString {
        append(profile.deviceName.trim())
        append(' ')
        append(profile.hardware.deviceMake.trim())
        append(' ')
        append(inferredMake)
        append(' ')
        append(profile.hardware.deviceModel.trim())
        append(' ')
        append(modelName)
        append(' ')
        append(profile.hardware.os.trim())
        append(' ')
        append(profile.hardware.platform.trim())
        append(' ')
        append(profile.deviceId.trim())
    }.lowercase()

    val os = profile.hardware.os.trim().lowercase()
    val platform = profile.hardware.platform.trim().lowercase()
    val make = inferredMake.trim().lowercase()

    if (os == "macos" || make == "apple" ||
        "macbook" in haystack || "imac" in haystack ||
        "mac mini" in haystack || "mac studio" in haystack || "mac pro" in haystack
    ) {
        return DeviceIconKind.MacDesktop
    }

    if (os == "windows" || "windows" in haystack || "surface" in haystack) {
        return DeviceIconKind.WindowsPc
    }

    if ("fold" in haystack || "foldable" in haystack || "razr fold" in haystack ||
        "galaxy z fold" in haystack || "pixel fold" in haystack
    ) {
        return DeviceIconKind.FoldablePhone
    }

    if (" flip" in haystack || "z flip" in haystack ||
        ("razr" in haystack && "fold" !in haystack)
    ) {
        return DeviceIconKind.FlipPhone
    }

    if ("pixel" in haystack || make == "google") {
        return DeviceIconKind.PixelPhone
    }

    if ("samsung" in haystack || make == "samsung" ||
        "galaxy s" in haystack || " s26" in haystack || " s25" in haystack ||
        "ultra" in haystack && ("s2" in haystack || "galaxy" in haystack)
    ) {
        return DeviceIconKind.SamsungPhone
    }

    if ("tablet" in haystack || "ipad" in haystack || " tab " in haystack) {
        return DeviceIconKind.AndroidTablet
    }

    if (platform == "desktop" || os == "linux" ||
        "desktop" in haystack || "laptop" in haystack || " pc" in haystack
    ) {
        return DeviceIconKind.GenericDesktop
    }

    if (platform == "android" || os == "android" ||
        "android" in haystack || "phone" in haystack
    ) {
        return DeviceIconKind.AndroidPhone
    }

    return DeviceIconKind.AndroidPhone
}

fun DeviceListRow.iconKind(): DeviceIconKind =
    resolveDeviceIconKind(
        DeviceIconProfile(
            deviceId = deviceId,
            deviceName = deviceName,
            hardware = DeviceHardwareProfile.from(this)
        )
    )

fun resolveFluxDrawable(profile: DeviceIconProfile): DrawableResource {
    val kind = resolveDeviceIconKind(profile)
    return when (kind) {
        DeviceIconKind.MacDesktop,
        DeviceIconKind.WindowsPc,
        DeviceIconKind.GenericDesktop -> Res.drawable.dev_flux_laptop

        DeviceIconKind.FoldablePhone -> Res.drawable.dev_flux_folding
        DeviceIconKind.FlipPhone -> Res.drawable.dev_flux_flip
        DeviceIconKind.AndroidTablet -> Res.drawable.dev_flux_tablet
        DeviceIconKind.PixelPhone,
        DeviceIconKind.SamsungPhone,
        DeviceIconKind.AndroidPhone -> Res.drawable.dev_flux_slab
    }
}

fun resolveFreestyleDrawable(profile: DeviceIconProfile): DrawableResource {
    val modelName = DeviceModelLookup.lookupMarketingName(profile.hardware.deviceModel) ?: profile.hardware.deviceModel
    val inferredMake = profile.hardware.deviceMake.ifBlank { DeviceModelLookup.inferMake(profile.hardware.deviceModel).orEmpty() }
    val haystack = buildString {
        append(profile.deviceName.trim())
        append(' ')
        append(profile.hardware.deviceMake.trim())
        append(' ')
        append(inferredMake)
        append(' ')
        append(profile.hardware.deviceModel.trim())
        append(' ')
        append(modelName)
        append(' ')
        append(profile.hardware.os.trim())
        append(' ')
        append(profile.hardware.platform.trim())
        append(' ')
        append(profile.deviceId.trim())
    }.lowercase()

    val os = profile.hardware.os.trim().lowercase()
    val make = inferredMake.trim().lowercase()

    if (os == "macos" || make == "apple" ||
        "macbook" in haystack || "imac" in haystack ||
        "mac mini" in haystack || "mac studio" in haystack || "mac pro" in haystack
    ) {
        return Res.drawable.dev_fs_macbook
    }

    if (os == "windows" || "windows" in haystack || "surface" in haystack) {
        return Res.drawable.dev_fs_windows
    }

    if ("pixel fold" in haystack || ("pixel" in haystack && ("fold" in haystack || "folding" in haystack))) {
        return Res.drawable.dev_fs_pixel_11_pro_fold
    }
    if ("pixel" in haystack || make == "google") {
        return Res.drawable.dev_fs_pixel_11_pro
    }

    if ("razr fold" in haystack || ("razr" in haystack && "fold" in haystack)) {
        return Res.drawable.dev_fs_motorola_razr_fold_2026
    }
    if ("moto edge" in haystack || "motorola edge" in haystack || ("edge" in haystack && make == "motorola")) {
        return Res.drawable.dev_fs_moto_edge
    }
    if ("razr" in haystack || "motorola" in haystack || make == "motorola" || "moto " in haystack) {
        return Res.drawable.dev_fs_moto_signature
    }

    val isHonor = "honor" in haystack || make == "honor"
    if (isHonor && ("magic v" in haystack || "magic-v" in haystack || "fold" in haystack)) {
        return Res.drawable.dev_fs_honor_magic_v5
    }
    if (isHonor && ("x9d" in haystack || "x9b" in haystack || "x9a" in haystack || "x9" in haystack)) {
        return Res.drawable.dev_fs_honor_x9d
    }
    if (isHonor && ("magic 8" in haystack || "magic8" in haystack || "magic" in haystack)) {
        return Res.drawable.dev_fs_magic8pro
    }

    val isOppo = "oppo" in haystack || make == "oppo" || "find x" in haystack || "find-x" in haystack
    if (isOppo) {
        if ("flip" in haystack) return Res.drawable.dev_fs_flip8
        if ("fold" in haystack) return Res.drawable.dev_fs_fold8
        return Res.drawable.dev_fs_oppo_find_x9_pro
    }

    if ("oneplus" in haystack || "1+" in haystack) {
        return Res.drawable.dev_fs_oneplus15
    }

    if ("poco" in haystack || "redmi" in haystack || "xiaomi" in haystack) {
        return Res.drawable.dev_fs_poco
    }

    if ("fold" in haystack || "foldable" in haystack || "galaxy z fold" in haystack) {
        return Res.drawable.dev_fs_fold8
    }

    if ("flip" in haystack || "z flip" in haystack) {
        return Res.drawable.dev_fs_flip8
    }

    return Res.drawable.dev_fs_generic
}
