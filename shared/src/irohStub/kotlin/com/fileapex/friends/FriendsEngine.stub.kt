package com.fileapex.friends

/** This build leaves out Iroh's native library, so Friends is not offered. */
actual fun embeddedFriendsEngine(): FriendsEngine? = null
