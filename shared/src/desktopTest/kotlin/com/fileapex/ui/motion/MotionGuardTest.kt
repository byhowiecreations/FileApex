package com.fileapex.ui.motion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Animation timing lives in `ui/motion`; a raw tween, spring or infinite loop elsewhere skips the reduced-motion switch. */
class MotionGuardTest {
    private val roots = listOf("src/commonMain/kotlin", "src/androidMain/kotlin", "src/desktopMain/kotlin", "src/jvmCommon/kotlin")
        .map { File(it) }.filter { it.exists() }
    private val raw = Regex("""(?<![A-Za-z.])(tween|spring|infiniteRepeatable)\(""")

    @Test
    fun `no raw animation spec outside the motion package`() {
        val offenders = mutableListOf<String>()
        for (root in roots) {
            root.walkTopDown().filter { it.isFile && it.extension == "kt" && !it.path.replace('\\', '/').contains("/ui/motion/") }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    val code = line.substringBefore("//")
                    if (!code.trimStart().startsWith("import ") && !code.trimStart().startsWith("*") && raw.containsMatchIn(code)) {
                        offenders += "${file.path}:${index + 1}: ${line.trim().take(100)}"
                    }
                }
            }
        }
        assertTrue("raw animation specs (use FileApexMotion):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
