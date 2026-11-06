package com.fileapex.iroh

import kotlinx.serialization.Serializable

/**
 * The self-hosted relay choice that every device of a cluster shares. It travels sealed inside the Iroh address
 * exchange between active paired devices. The change with the newest [changedAtMs] wins.
 */
@Serializable
data class IrohRelaySetting(val enabled: Boolean, val url: String, val token: String, val changedAtMs: Long) {
    companion object {
        private const val MAX_FUTURE_MS = 10 * 60_000L
        private const val MAX_TOKEN_LENGTH = 256

        /**
         * The setting to store after [remote] arrived, or null to keep [local]. A time further ahead than a
         * wrong clock explains is refused, so one bad clock cannot block every later change.
         */
        fun adopt(local: IrohRelaySetting, remote: IrohRelaySetting?, nowMs: Long): IrohRelaySetting? {
            if (remote == null || remote.changedAtMs <= 0L || remote.changedAtMs > nowMs + MAX_FUTURE_MS) return null
            val token = remote.token
            if (token.length > MAX_TOKEN_LENGTH || token.any { it.isWhitespace() || it.isISOControl() }) return null
            val url = when (val checked = IrohRelayUrl.check(remote.url)) {
                is IrohRelayUrlCheck.Valid -> checked.url
                is IrohRelayUrlCheck.Invalid -> if (remote.enabled) return null else ""
            }
            val adopted = IrohRelaySetting(remote.enabled, url, token, remote.changedAtMs)
            if (adopted.changedAtMs < local.changedAtMs) return null
            if (adopted.changedAtMs == local.changedAtMs && !adopted.winsTieOver(local)) return null
            return adopted
        }
    }

    /** Equal timestamps must end on one value everywhere, so the larger value wins on both sides. */
    private fun winsTieOver(other: IrohRelaySetting): Boolean =
        compareValuesBy(this, other, { it.enabled }, { it.url }, { it.token }) > 0
}
