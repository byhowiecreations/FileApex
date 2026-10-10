package com.fileapex.platform

/**
 * Moves entries into the device trash.
 * @return true when they are already in the trash. False when the system is still asking the user.
 */
expect fun trashLocalEntries(absolutePaths: List<String>): Boolean

/**
 * Trash for a request that arrived over the network: never raises a system confirmation on this device.
 * Android parks the entries in the app trash (restorable from Tools); desktop uses the system trash.
 */
expect fun trashLocalEntriesQuietly(absolutePaths: List<String>)
