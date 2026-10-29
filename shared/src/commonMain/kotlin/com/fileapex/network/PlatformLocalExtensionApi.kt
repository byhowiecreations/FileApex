package com.fileapex.network

/** The Finder extension routes exist only on the Mac app; everywhere else this is null and the routes are not registered. */
expect fun platformLocalExtensionApi(): LocalExtensionApi?
