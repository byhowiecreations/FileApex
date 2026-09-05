package com.fileapex.platform

import com.fileapex.domain.model.RemoteFileItem

expect fun fastScanDirectory(absolutePath: String): Pair<List<RemoteFileItem>, List<RemoteFileItem>>
