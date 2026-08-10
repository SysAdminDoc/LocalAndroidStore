package com.sysadmin.lasstore.ui.log

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class LogScreenInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun seedEvidence() {
        ServiceLocator.logger.clearDiagnostics()
        ServiceLocator.logger.clearCrashEvidence()
        ServiceLocator.audit.clear()
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val image = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val uri = requireNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/LocalAndroidStoreTest",
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                },
            ),
        )
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { output ->
            image.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
            null,
            null,
        )
    }
}
