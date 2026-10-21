package com.fileapex.structure

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keeps the layers pointing one way: screens talk to view models, view models to domain and data, and the network and
 * data code never reach back up into screens. Files that already break a rule are listed so they can be fixed one at a
 * time; a new file that breaks one fails here.
 */
class LayerGuardTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin")
        .map { File(it) }.filter { it.exists() }

    private class Rule(val name: String, val from: List<String>, val mustNotImport: String, val knownBreaks: Set<String> = emptySet())

    private val rules = listOf(
        Rule("screens do not call the network layer", listOf("ui"), "com.fileapex.network."),
        Rule("presentation does not import screens", listOf("presentation"), "com.fileapex.ui."),
        Rule("domain does not import screens", listOf("domain"), "com.fileapex.ui."),
        Rule("network does not import screens", listOf("network"), "com.fileapex.ui."),
        Rule("network does not import presentation", listOf("network"), "com.fileapex.presentation."),
        Rule(
            "data does not import screens", listOf("data"), "com.fileapex.ui.",
            knownBreaks = setOf("data/settings/AppTheme.kt")
        ),
        Rule(
            "domain and data do not import presentation", listOf("domain", "data"), "com.fileapex.presentation.",
            knownBreaks = setOf(
                "domain/demo/DemoModeState.kt", "domain/transfer/ExplorerTransferManager.kt", "domain/device/DeviceOrderCoordinator.kt",
                "domain/preview/FilePreviewManager.kt", "domain/browse/BrowserCoordinator.kt",
                "data/settings/AppSettings.kt", "data/settings/BaseAppSettings.kt"
            )
        )
    )

    @Test
    fun layersPointOneWay() {
        val problems = mutableListOf<String>()
        for (rule in rules) {
            for (root in roots) {
                for (area in rule.from) {
                    val dir = File(root, "com/fileapex/$area")
                    if (!dir.exists()) continue
                    dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                        val relative = file.relativeTo(File(root, "com/fileapex")).path.replace(File.separatorChar, '/')
                        val breaks = file.useLines { lines -> lines.any { it.startsWith("import ${rule.mustNotImport}") } }
                        if (breaks && relative !in rule.knownBreaks) problems += "${rule.name}: $relative"
                    }
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }
}
