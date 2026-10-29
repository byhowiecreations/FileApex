package com.fileapex.platform

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class AndroidApiLevelGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    private fun sources() = listOf("shared/src", "composeApp/src").map { File(root, it) }.flatMap { dir ->
        dir.walkTopDown().onEnter { it.name != "build" }
            .filter { it.isFile && it.extension in setOf("kt", "java") && "Test" !in it.path && "/desktop" !in it.path }.toList()
    }

    // ByteArrayOutputStream.toString(Charset) is Java 10; Android 11 (SDK 30) has no such method and throws NoSuchMethodError.
    // Only receivers declared as ByteArrayOutputStream count: Kotlin's ByteArray.toString(Charset) is an extension and safe.
    @Test
    fun noByteArrayOutputStreamCallsTheJava10ToStringCharset() {
        val declared = Regex("""(?:val|var)\s+(\w+)\s*(?::\s*ByteArrayOutputStream\b|=\s*ByteArrayOutputStream\()""")
        val offenders = sources().flatMap { file ->
            val text = file.readText()
            val names = declared.findAll(text).map { it.groupValues[1] }.toSet()
            names.flatMap { name ->
                Regex("""\b${name}\.toString\(\s*(Charsets\.\w+|StandardCharsets\.\w+|Charset\b[^)]*)\)""").findAll(text)
                    .map { "${file.name}: $name.toString(charset)" }
            }
        }
        assertEquals("use out.toString(Charsets.UTF_8.name())", emptyList<String>(), offenders)
    }

    @Test
    fun theDecodeUsedInsteadStaysJava8Compatible() {
        val out = ByteArrayOutputStream().apply { write("héllo".toByteArray(Charsets.UTF_8)) }
        assertEquals("héllo", out.toString(Charsets.UTF_8.name()))
    }
}
