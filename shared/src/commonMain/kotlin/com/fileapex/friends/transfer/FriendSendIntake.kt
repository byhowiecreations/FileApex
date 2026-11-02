package com.fileapex.friends.transfer

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Decides what a friend send accepts and why anything is dropped, so no send disappears without a reason in the log. */
object FriendSendIntake {
    enum class Drop { NO_FRIEND, NOTHING_CHOSEN, NOT_A_FILE, UNAVAILABLE }

    class Plan(val files: List<File>, val drops: List<Drop>) {
        /** Reasons and counts only: a path or a file name is the user's data and stays out of the log. */
        fun logLine(): String = "a send to a friend kept ${files.size} file(s), dropped ${drops.groupingBy { it }.eachCount()}"
    }

    fun plan(friendKnown: Boolean, paths: List<String>, available: Boolean = true): Plan = planFiles(friendKnown, paths.map { File(it) }, available)

    /** Blocking: checks every file, so callers run it on an I/O dispatcher. */
    fun planFiles(friendKnown: Boolean, files: List<File>, available: Boolean = true): Plan = when {
        !available -> Plan(emptyList(), listOf(Drop.UNAVAILABLE))
        !friendKnown -> Plan(emptyList(), listOf(Drop.NO_FRIEND))
        files.isEmpty() -> Plan(emptyList(), listOf(Drop.NOTHING_CHOSEN))
        else -> files.partition { it.isFile && it.canRead() }.let { (kept, rest) -> Plan(kept, rest.map { Drop.NOT_A_FILE }) }
    }

    private val droppedInternal = MutableSharedFlow<Drop>(extraBufferCapacity = 4)

    /** Why a send the user asked for was not queued; the screens say so instead of going quiet. */
    val dropped: SharedFlow<Drop> = droppedInternal.asSharedFlow()

    /** The files to send, after logging by reason and reporting on [dropped] whatever was left out. */
    suspend fun accept(friendKnown: Boolean, paths: List<String>, available: Boolean = true): List<File> =
        acceptFiles(friendKnown, paths.map { File(it) }, available)

    suspend fun acceptFiles(friendKnown: Boolean, files: List<File>, available: Boolean = true): List<File> {
        val plan = withContext(Dispatchers.IO) { planFiles(friendKnown, files, available) }
        println("FriendsHub: ${plan.logLine()}")
        plan.drops.distinct().forEach { droppedInternal.emit(it) }
        return plan.files
    }
}
