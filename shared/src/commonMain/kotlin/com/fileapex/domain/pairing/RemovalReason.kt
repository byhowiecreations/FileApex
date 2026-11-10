package com.fileapex.domain.pairing

/**
 * Why a device was removed, chosen where the removal is made. [USER_REMOVED] is the Remove button on a roster device and
 * the only reason a removed device acts on; [LEFT_CLUSTER] is a device leaving on its own from Settings; [SUPERSEDED] is an automatic
 * clean-up of an outdated copy. Every reason but [USER_REMOVED] is only logged as a hint.
 */
enum class RemovalReason(val code: String) {
    USER_REMOVED("user_removed"),
    SUPERSEDED("superseded"),
    LEFT_CLUSTER("left_cluster")
}
