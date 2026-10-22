package com.fileapex.friends

/** The choices for the largest file accepted from a friend, in MB; 0 is no limit. */
object FriendsLimits {
    val choicesMb: List<Int> = listOf(10, 25, 100, 250, 0)

    fun maxBytes(mb: Int): Long = if (mb <= 0) Long.MAX_VALUE else mb * 1024L * 1024L
}
