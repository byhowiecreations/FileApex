package com.fileapex.platform

/** [continuous] keeps sounding until the user stops it on this device; computers only ever play the short beep. */
expect fun triggerLocalLocatorSound(continuous: Boolean = false)

/** Silences a continuous locator alarm; does nothing when none is sounding. */
expect fun stopLocalLocatorSound()
