package com.fileapex.platform

/** True on macOS/desktop JVM host — used for desktop-only LAN poll loop. */
expect fun isDesktopHost(): Boolean
