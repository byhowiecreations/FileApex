package com.fileapex.presentation

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
    val haystack = buildString {
        append(profile.deviceName.trim())
        append(' ')
        append(profile.hardware.deviceMake.trim())
        append(' ')
        append(profile.hardware.deviceModel.trim())
        append(' ')
        append(profile.hardware.os.trim())
        append(' ')
        append(profile.hardware.platform.trim())
        append(' ')
        append(profile.deviceId.trim())
    }.lowercase()

    val os = profile.hardware.os.trim().lowercase()
    val platform = profile.hardware.platform.trim().lowercase()
    val make = profile.hardware.deviceMake.trim().lowercase()

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
