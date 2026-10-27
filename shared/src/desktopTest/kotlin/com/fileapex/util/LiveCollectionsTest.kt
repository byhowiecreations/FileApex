package com.fileapex.util

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveCollectionsTest {
    @Test
    fun aCollectionThatEmptiesAfterItsSizeIsReadGivesAnEmptyCopy() {
        val emptiesUnderneath = object : AbstractCollection<String>() {
            override val size: Int get() = 1
            override fun iterator(): Iterator<String> = emptyList<String>().iterator()
        }
        assertEquals(emptyList<String>(), emptiesUnderneath.copyOfLive())
    }

    @Test
    fun aConcurrentMapViewIsCopiedInFull() {
        val map = ConcurrentHashMap<String, Int>().apply { put("a", 1); put("b", 2) }
        assertEquals(setOf("a", "b"), map.keys.copyOfLive().toSet())
        assertEquals(setOf(1, 2), map.values.copyOfLive().toSet())
    }

    /**
     * toList() and toSet() on a concurrent collection fail with NoSuchElementException when the last element goes
     * away while they run. Production code copies those through copyOfLive().
     */
    @Test
    fun concurrentCollectionsAreNeverSnapshotWithToListOrToSet() {
        val declaration = Regex("""\b(?:val|var)\s+(\w+)\b[^=\n]*=\s*(?:java\.util\.concurrent\.)?(?:ConcurrentHashMap|ConcurrentLinkedQueue|CopyOnWriteArray\w*|java\.util\.Collections\.newSetFromMap)""")
        val roots = listOf("src/commonMain/kotlin", "src/jvmCommon/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin")
            .map { File(it) }.filter { it.exists() }
        val offenders = roots.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                val lines = file.readLines()
                val names = declaration.findAll(lines.joinToString("\n")).map { it.groupValues[1] }.toSet()
                names.flatMap { name ->
                    val use = Regex("""\b$name\b(\.(keys|values|entries))?\.(toList|toSet)\(""")
                    lines.withIndex().filter { (_, line) -> use.containsMatchIn(line) }.map { (index, _) -> "${file.name}:${index + 1}" }
                }
            }.toList()
        }
        assertEquals(emptyList<String>(), offenders)
    }
}
