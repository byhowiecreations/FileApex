package com.fileapex.platform

/** Battery saver on, or battery low and not charging. Desktop always reports false. */
expect fun isTransferLowPowerMode(): Boolean
