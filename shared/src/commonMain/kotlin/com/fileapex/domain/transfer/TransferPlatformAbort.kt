package com.fileapex.domain.transfer

/** Closes platform transfer connections that are not Java sockets. */
expect fun abortInFlightPlatformTransfers()
