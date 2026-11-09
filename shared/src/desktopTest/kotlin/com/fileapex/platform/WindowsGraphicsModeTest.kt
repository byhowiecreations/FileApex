package com.fileapex.platform

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsGraphicsModeTest {
    private fun tempDir(): File = Files.createTempDirectory("graphics-mode").toFile()

    @Test
    fun textMapsToAModeAndAnythingUnknownMeansAutomatic() {
        assertEquals(WindowsGraphicsMode.Direct3D, WindowsGraphicsMode.fromText("direct3d"))
        assertEquals(WindowsGraphicsMode.Direct3D, WindowsGraphicsMode.fromText("  Direct3D \n"))
        assertEquals(WindowsGraphicsMode.OpenGL, WindowsGraphicsMode.fromText("OPENGL"))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.fromText("automatic"))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.fromText(""))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.fromText("vulkan"))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.fromText(null))
    }

    @Test
    fun eachModeNamesTheSkikoRenderApi() {
        assertEquals("DIRECT3D", WindowsGraphicsMode.Direct3D.renderApi)
        assertEquals("OPENGL", WindowsGraphicsMode.OpenGL.renderApi)
        assertEquals("OPENGL", WindowsGraphicsMode.Automatic.renderApi)
    }

    @Test
    fun theChoiceIsKeptInAPlainFileSoItCanBeReadBeforeAnythingElseStarts() {
        val dir = tempDir()
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.load(dir))
        WindowsGraphicsMode.save(dir, WindowsGraphicsMode.Direct3D)
        assertEquals(WindowsGraphicsMode.Direct3D, WindowsGraphicsMode.load(dir))
        assertEquals("direct3d", File(dir, WindowsGraphicsMode.FILE_NAME).readText().trim())
        WindowsGraphicsMode.save(dir, WindowsGraphicsMode.Automatic)
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.load(dir))
    }

    @Test
    fun aMissingOrUnreadableFileMeansAutomatic() {
        val dir = tempDir()
        File(dir, WindowsGraphicsMode.FILE_NAME).writeBytes(byteArrayOf(0, 1, 2, -1))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.load(dir))
        assertEquals(WindowsGraphicsMode.Automatic, WindowsGraphicsMode.load(File(dir, "no-such-folder")))
    }

    @Test
    fun anExplicitRenderApiFromTheLauncherOrEnvironmentStillWins() {
        assertEquals("DIRECT3D", WindowsGraphicsMode.effectiveRenderApi(property = "DIRECT3D", mode = WindowsGraphicsMode.OpenGL))
        assertEquals("OPENGL", WindowsGraphicsMode.effectiveRenderApi(property = "", mode = WindowsGraphicsMode.OpenGL))
        assertEquals("DIRECT3D", WindowsGraphicsMode.effectiveRenderApi(property = null, mode = WindowsGraphicsMode.Direct3D))
    }

    @Test
    fun theLauncherNoLongerFixesTheRendererAndSwitchesOffJava2dDirect3d() {
        val build = (File("composeApp/build.gradle.kts").takeIf { it.isFile } ?: File("../composeApp/build.gradle.kts")).readText()
        val windowsBlock = build.substringAfter("if (isWindowsHost()) {\n            // ").substringBefore("buildTypes.release.proguard")
        assertFalse("the renderer is chosen in code now", windowsBlock.contains("-Dskiko.renderApi"))
        assertTrue(windowsBlock.contains("-Dsun.java2d.d3d=false"))
    }

    @Test
    fun skikoStaysAtTheVersionComposePinsBecauseANewerOneBreaksSkiaLayer() {
        val build = (File("composeApp/build.gradle.kts").takeIf { it.isFile } ?: File("../composeApp/build.gradle.kts")).readText()
        assertFalse("Skiko 0.156.1 under Compose 1.11.1 fails at startup with NoSuchMethodError on SkiaLayer.<init>", build.contains("org.jetbrains.skiko\") useVersion"))
        assertFalse(build.contains("resolutionStrategy.eachDependency"))
    }
}
