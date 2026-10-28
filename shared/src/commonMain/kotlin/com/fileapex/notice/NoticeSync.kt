package com.fileapex.notice

enum class NoticeSyncOutcome { Skipped, Throttled, NotModified, Updated, Rejected, Failed }

object NoticeSync {
    const val OFFICIAL_URL = "https://raw.githubusercontent.com/byhowiecreations/FileApex/main/notice/notice.json"
    const val MIN_SUCCESS_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val MIN_ATTEMPT_INTERVAL_MS = 15L * 60 * 1000

    /**
     * One check. Nothing leaves the device while the setting is off or before the explanation was shown. A failed request
     * keeps the last good file; a manual check ignores the spacing between requests.
     */
    suspend fun check(
        store: NoticeSettingsStore,
        transport: NoticeTransport,
        url: String,
        publicKeys: List<ByteArray>,
        nowMs: Long,
        manual: Boolean
    ): NoticeSyncOutcome {
        if (!store.enabled.value || !store.choiceMade.value) return NoticeSyncOutcome.Skipped
        if (!manual && (nowMs - store.lastSuccessMs() < MIN_SUCCESS_INTERVAL_MS || nowMs - store.lastAttemptMs() < MIN_ATTEMPT_INTERVAL_MS)) {
            return NoticeSyncOutcome.Throttled
        }
        store.setLastAttemptMs(nowMs)
        return when (val response = transport.get(url, store.etag())) {
            NoticeHttpResponse.Failed -> NoticeSyncOutcome.Failed
            NoticeHttpResponse.NotModified -> {
                store.setLastSuccessMs(nowMs)
                NoticeSyncOutcome.NotModified
            }
            is NoticeHttpResponse.Ok -> {
                when (val read = NoticeFileReader.read(response.body, publicKeys, store.highestSerial())) {
                    is NoticeReadResult.Accepted -> {
                        store.saveAccepted(response.body, read.payload.serial, response.etag, read.payload.notices.map { it.id }.toSet())
                        store.setLastSuccessMs(nowMs)
                        NoticeSyncOutcome.Updated
                    }
                    else -> {
                        println("NoticeSync: file rejected (${read::class.simpleName})")
                        NoticeSyncOutcome.Rejected
                    }
                }
            }
        }
    }
}
