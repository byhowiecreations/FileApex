package com.fileapex.drop

/** This build leaves out Iroh's native library, so link sharing is not offered. */
actual fun embeddedGuestDropEngine(): GuestDropEngine? = null
