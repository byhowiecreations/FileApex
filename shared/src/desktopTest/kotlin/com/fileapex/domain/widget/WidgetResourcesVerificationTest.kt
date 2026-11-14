package com.fileapex.domain.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetResourcesVerificationTest {

    private fun composeAppDir(): File {
        val fromModule = File("../composeApp")
        if (fromModule.isDirectory) return fromModule
        val fromRoot = File("composeApp")
        if (fromRoot.isDirectory) return fromRoot
        error("Could not locate composeApp directory from ${File(".").absolutePath}")
    }

    @Test
    fun allWidgetProvidersDisablePollingAndStayPlayCompliant() {
        val root = composeAppDir()
        val xmlDir = File(root, "src/androidMain/res/xml")
        val widgetInfos = listOf(
            "quick_actions_widget_info.xml",
            "device_card_widget_info.xml",
            "drop_target_widget_info.xml",
            "bulletin_board_widget_info.xml"
        )
        widgetInfos.forEach { fileName ->
            val file = File(xmlDir, fileName)
            assertTrue("Missing $fileName", file.isFile)
            val content = file.readText()
            assertTrue("$fileName must set updatePeriodMillis=\"0\"", content.contains("""android:updatePeriodMillis="0""""))
        }
        listOf(
            "quick_actions_1x1_widget_info.xml",
            "quick_actions_2x1_widget_info.xml",
            "quick_actions_4x1_widget_info.xml"
        ).forEach { legacy ->
            assertFalse("Legacy $legacy must be removed", File(xmlDir, legacy).exists())
        }

        val quickXml = File(xmlDir, "quick_actions_widget_info.xml").readText()
        assertTrue(quickXml.contains("""android:minWidth="40dp""""))
        assertTrue(quickXml.contains("""android:minHeight="40dp""""))
        assertFalse(quickXml.contains("configuration_optional"))

        val dropXml = File(xmlDir, "drop_target_widget_info.xml").readText()
        assertTrue(dropXml.contains("""android:minWidth="40dp""""))
        assertTrue(dropXml.contains("""android:minHeight="40dp""""))
        assertFalse(dropXml.contains("configuration_optional"))

        val cardXml = File(xmlDir, "device_card_widget_info.xml").readText()
        assertTrue(cardXml.contains("""android:minHeight="90dp""""))
        assertFalse(cardXml.contains("configuration_optional"))

        val manifest = File(root, "src/androidMain/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("READ_MEDIA_IMAGES"))
        assertFalse(manifest.contains("READ_MEDIA_VIDEO"))
        assertFalse(manifest.contains("QuickActions1x1WidgetProvider"))
        assertFalse(manifest.contains("QuickActions2x1WidgetProvider"))
        assertFalse(manifest.contains("QuickActions4x1WidgetProvider"))
        listOf(
            ".widget.QuickActionsWidgetProvider",
            ".widget.DeviceCardWidgetProvider",
            ".widget.DropTargetWidgetProvider",
            ".widget.BulletinBoardWidgetProvider",
            ".widget.BulletinQuickPostActivity",
            ".widget.WidgetConfigureActivity",
            ".widget.WidgetFilePickActivity",
            ".widget.WidgetDevicePickActivity",
            ".widget.WidgetBatteryPopupActivity",
            ".widget.WidgetActionReceiver",
            ".widget.BulletinWidgetRemoteViewsService"
        ).forEach { component ->
            assertTrue("Manifest missing $component", manifest.contains(component))
        }
    }

    @Test
    fun composeAppAndroidStringsMatchAcrossLocales() {
        val resDir = File(composeAppDir(), "src/androidMain/res")
        val keyRe = Regex("""<string\s+name="([^"]+)"""")
        val en = keyRe.findAll(File(resDir, "values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val es = keyRe.findAll(File(resDir, "values-es/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        val zh = keyRe.findAll(File(resDir, "values-zh-rCN/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        assertEquals(en, es)
        assertEquals(en, zh)
    }

    @Test
    fun widgetFilePickActivityPreservesPickerResultAndWidgetsUseFileApexTheme() {
        val root = composeAppDir()
        val manifest = File(root, "src/androidMain/AndroidManifest.xml").readText()
        val pickBlock = Regex("""<activity\b[^>]*WidgetFilePickActivity.*?(?:/>|</activity>)""", setOf(RegexOption.DOT_MATCHES_ALL))
            .find(manifest)?.value
            ?: error("Could not locate WidgetFilePickActivity declaration in AndroidManifest.xml")
        assertFalse(
            "WidgetFilePickActivity must not set android:noHistory=\"true\" because OpenMultipleDocuments destroys it before result delivery",
            pickBlock.contains("""android:noHistory="true"""")
        )

        val widgetDir = File(root, "src/androidMain/kotlin/com/fileapex/widget")
        listOf(
            "WidgetConfigureActivity.kt",
            "WidgetBatteryPopupActivity.kt",
            "WidgetDevicePickActivity.kt",
            "BulletinQuickPostActivity.kt"
        ).forEach { fileName ->
            val src = File(widgetDir, fileName).readText()
            assertTrue("$fileName must use FileApexWidgetTheme", src.contains("FileApexWidgetTheme"))
            assertFalse("$fileName must not use raw default darkColorScheme()", src.contains("darkColorScheme("))
            assertFalse("$fileName must not use raw default lightColorScheme()", src.contains("lightColorScheme("))
        }
        val configureSrc = File(widgetDir, "WidgetConfigureActivity.kt").readText()
        assertTrue(
            "WidgetConfigureActivity must render action icons via WidgetUiFormatter.iconForAction",
            configureSrc.contains("WidgetUiFormatter.iconForAction")
        )
    }

    @Test
    fun phoneSimpleHomeIncludesLiveTransferBannerAndBackupDropsScreenshotHint() {
        val sharedRoot = File(composeAppDir().parentFile, "shared/src/commonMain/kotlin/com/fileapex")
        val simpleHomeSrc = File(sharedRoot, "ui/adaptive/SimpleHome.kt").readText()
        assertTrue(
            "SimpleHome must mount LiveTransferBanner so phone transfers remain visible",
            simpleHomeSrc.contains("LiveTransferBanner(")
        )

        val backupPageSrc = File(sharedRoot, "ui/settings/SettingsBackupPage.kt").readText()
        assertFalse(
            "Backup & Sync page must drop the redundant 'What about screenshots?' prompt",
            backupPageSrc.contains("backup_what_about_screenshots")
        )

        val settingsComponentsSrc = File(sharedRoot, "ui/settings/SettingsComponents.kt").readText()
        assertFalse(
            "BACKUP_SYNC is no longer an advanced setting row",
            settingsComponentsSrc.contains("add(SettingsMenuIds.BACKUP_SYNC)")
        )
    }
}
