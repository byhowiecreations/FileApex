package com.fileapex.network

import com.fileapex.platform.DesktopLocalExtensionApi
import com.fileapex.platform.DesktopPlatformPaths

actual fun platformLocalExtensionApi(): LocalExtensionApi? =
    if (DesktopPlatformPaths.isMacOs()) DesktopLocalExtensionApi.live() else null
