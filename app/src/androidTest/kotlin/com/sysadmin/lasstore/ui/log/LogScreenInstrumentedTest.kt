package com.sysadmin.lasstore.ui.log

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.test.PrivateScreenshotStore
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LogScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screenshots = PrivateScreenshotStore(context)

    @Before
    fun seedEvidence() {
        ServiceLocator.logger.clearDiagnostics()
        ServiceLocator.logger.clearCrashEvidence()
        ServiceLocator.audit.clear()
        screenshots.cleanup()
        ServiceLocator.logger.info("Catalog", "Catalog refresh completed")
        ServiceLocator.logger.warn("Installer", "Install requires review")
        ServiceLocator.logger.error("Installer", "Install failed safely")
        ServiceLocator.audit.uninstallInitiated(
            applicationId = "com.example.journal",
            source = "owner/repo",
        )
    }

    @After
    fun cleanup() {
        ServiceLocator.logger.clearDiagnostics()
        ServiceLocator.logger.clearCrashEvidence()
        ServiceLocator.audit.clear()
        screenshots.cleanup()
    }

    @Test
    fun journalSeparatesEvidenceAndExplainsIndependentClearActions() {
        composeRule.setContent {
            LocalAndroidStoreTheme {
                LogScreen()
            }
        }

        composeRule.onNodeWithText("Diagnostics 3").assertExists()
        composeRule.onNodeWithText("Install audit 1").assertExists().performClick()
        composeRule.onNodeWithText("com.example.journal").assertExists()
        composeRule.onNodeWithContentDescription("Export redacted support bundle").assertExists()
        saveScreenshot("activity-journal.png")

        composeRule.onNodeWithContentDescription("Clear selected journal").performClick()
        composeRule.onAllNodesWithText("Clear install audit").assertCountEquals(2)
        composeRule.onNodeWithText(
            "Delete local install, uninstall, and publisher-trust decision history.",
            substring = true,
        ).assertExists()
        composeRule.onNodeWithText("Cancel").performClick()

        composeRule.onNodeWithText("Crash evidence 1").performClick()
        composeRule.onNodeWithText("Install failed safely").assertExists()
    }

    private fun saveScreenshot(name: String) {
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        screenshots.save(name, image)
    }
}
