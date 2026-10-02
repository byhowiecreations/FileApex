package com.fileapex.data.settings

data class ThemeTraits(
    val glassChrome: Boolean,
    val glassNotesSurfaces: Boolean,
    val reorderAccent: Boolean,
    val spatialHome: Boolean,
    val desktopHoverPopOver: Boolean,
    val fluxSurfaces: Boolean,
    val orbitalHome: Boolean,
    val canvasHome: Boolean,
)

private val cleanTraits = ThemeTraits(
    glassChrome = false,
    glassNotesSurfaces = false,
    reorderAccent = false,
    spatialHome = false,
    desktopHoverPopOver = true,
    fluxSurfaces = false,
    orbitalHome = false,
    canvasHome = false,
)

private val fluxGlassTraits = ThemeTraits(
    glassChrome = true,
    glassNotesSurfaces = true,
    reorderAccent = true,
    spatialHome = false,
    desktopHoverPopOver = true,
    fluxSurfaces = true,
    orbitalHome = false,
    canvasHome = false,
)

private val kineticSphereTraits = ThemeTraits(
    glassChrome = true,
    glassNotesSurfaces = true,
    reorderAccent = false,
    spatialHome = true,
    desktopHoverPopOver = false,
    fluxSurfaces = false,
    orbitalHome = true,
    canvasHome = false,
)

private val freestyleTraits = ThemeTraits(
    glassChrome = true,
    glassNotesSurfaces = false,
    reorderAccent = true,
    spatialHome = true,
    desktopHoverPopOver = false,
    fluxSurfaces = false,
    orbitalHome = false,
    canvasHome = true,
)

val AppTheme.traits: ThemeTraits
    get() = when (this) {
        AppTheme.CLEAN -> cleanTraits
        AppTheme.FLUX_GLASS -> fluxGlassTraits
        AppTheme.KINETIC_SPHERE -> kineticSphereTraits
        AppTheme.FREESTYLE -> freestyleTraits
    }
