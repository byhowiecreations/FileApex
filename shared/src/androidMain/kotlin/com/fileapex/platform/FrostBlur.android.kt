package com.fileapex.platform

import android.os.Build

actual fun frostBlurSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
