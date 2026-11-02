package com.fileapex.friends.transfer

import java.io.FileNotFoundException
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException

/** Why a send failed, as a short code the transfer row turns into words. Codes only: nothing about the file, friend or address. */
object FriendFailure {
    const val UNREACHABLE = "unreachable"
    const val TIMEOUT = "timeout"
    const val LOST = "lost"
    const val ANSWER = "answer"
    const val FILE = "file"
    const val GONE = "gone"

    fun of(e: Throwable): String {
        val message = e.message.orEmpty()
        return when {
            e is FileNotFoundException || e is NoSuchFileException || e is AccessDeniedException -> FILE
            message.startsWith("friend open") -> UNREACHABLE
            message.contains("timed out") && !message.contains("ended") -> TIMEOUT
            else -> LOST
        }
    }
}
