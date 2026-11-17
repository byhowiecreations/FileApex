package com.fileapex.platform

import java.io.IOException

/** An archive was refused before or during extraction because it is oversized, too dense or unsafe. */
class ArchiveRefusedException(reason: String) : IOException(reason)
