package com.fileapex.domain.clipboard

object ClipboardPendingOptInStore {
    private var pending: ClipboardSendRequest? = null

    fun setPending(payload: ClipboardSendRequest) {
        pending = payload
    }

    fun consume(): ClipboardSendRequest? {
        val current = pending
        pending = null
        return current
    }

    fun clear() {
        pending = null
    }
}
