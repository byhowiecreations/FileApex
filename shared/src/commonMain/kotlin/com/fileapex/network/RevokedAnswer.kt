package com.fileapex.network

import com.fileapex.di.FileApexServices
import com.fileapex.membership.removalIsClusterKick
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/** `401 revoked` with the removal's stamp, reason and remover, so a device that was away learns why it is out. */
internal suspend fun answerRevoked(call: ApplicationCall, deviceId: String, selfId: String) {
    val repository = FileApexServices.deviceRepository
    call.response.headers.append("X-FileApex-Status", "revoked")
    call.response.headers.append("X-FileApex-Revoked-At", repository.revokedVersion(deviceId).toString())
    repository.revokedDetail(deviceId).let { detail ->
        val clusterKick = FileApexServices.membershipHub
            ?.chainLog?.view()?.state?.removalIsClusterKick(selfId, deviceId) == true
        if (clusterKick && detail.reason.isNotBlank()) {
            call.response.headers.append("X-FileApex-Revoked-Reason", detail.reason)
        }
        if (detail.removedBy.isNotBlank()) call.response.headers.append("X-FileApex-Revoked-By", detail.removedBy)
    }
    call.respond(HttpStatusCode.Unauthorized, "revoked")
}
