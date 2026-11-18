package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every whole-package `-keep` stops R8 from shrinking that library (measured 2026-11-17: Ktor engines 236 KB, zxing 132 KB,
 * Ktor serialization 121 KB of DEX). Code that is named directly is found by R8 on its own, so only what is loaded by
 * name (reflection, JNI, service loader) may be kept by package.
 */
class ProguardRulesGuardTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val rules: List<String> = File(root, "composeApp/proguard-rules.pro").readLines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun hasRule(text: String) = rules.any { it == text }

    @Test
    fun libraryCodeNamedInSourceIsNotKeptByPackage() {
        val wholePackageKeeps = listOf(
            "-keep class io.ktor.serialization.** { *; }",
            "-keep class io.ktor.serialization.kotlinx.** { *; }",
            "-keep class io.ktor.serialization.kotlinx.json.** { *; }",
            "-keep class io.ktor.server.cio.** { *; }",
            "-keep class io.ktor.client.cio.** { *; }",
            "-keep class io.ktor.network.selector.** { *; }",
            "-keep class io.ktor.network.sockets.** { *; }",
            "-keep class androidx.sqlite.driver.bundled.** { *; }",
            "-keep class com.journeyapps.barcodescanner.** { *; }",
            "-keep class androidx.sharetarget.** { *; }"
        )
        val present = wholePackageKeeps.filter { hasRule(it) }
        assertTrue("whole-package keeps that R8 does not need:\n" + present.joinToString("\n"), present.isEmpty())
    }

    @Test
    fun whatIsLoadedByNameStaysKept() {
        val required = listOf(
            "-keep class * implements io.ktor.serialization.kotlinx.KotlinxSerializationExtensionProvider { *; }",
            "-keepclasseswithmembers class androidx.sqlite.driver.bundled.** { native <methods>; }",
            "-keep class moe.shizuku.** { *; }",
            "-keep class rikka.shizuku.** { *; }",
            "-keep class go.** { *; }",
            "-keep class tsnetbridge.** { *; }",
            "-keep class computer.iroh.** { *; }",
            "-keep class com.sun.jna.** { *; }"
        )
        val missing = required.filterNot { hasRule(it) }
        assertTrue("rules for code reached by name are gone:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
