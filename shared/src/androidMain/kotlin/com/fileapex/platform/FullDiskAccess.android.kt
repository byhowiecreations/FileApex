package com.fileapex.platform

actual fun fullDiskAccessSupported(): Boolean = false

actual fun isFullDiskAccessGranted(): Boolean = true

actual fun openFullDiskAccessSettings() = Unit
