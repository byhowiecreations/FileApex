package com.fileapex.platform

import java.util.concurrent.atomic.AtomicBoolean

internal object ClipboardShareSuppressor {
    private val applying = AtomicBoolean(false)

    var isApplyingRemote: Boolean
        get() = applying.get()
        set(value) {
            applying.set(value)
        }
}
