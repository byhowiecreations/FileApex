package com.fileapex.platform

/**
 * Moves entries into the device trash.
 * @return true when they are already in the trash. False when the system is still asking the user.
 */
expect fun trashLocalEntries(absolutePaths: List<String>): Boolean
