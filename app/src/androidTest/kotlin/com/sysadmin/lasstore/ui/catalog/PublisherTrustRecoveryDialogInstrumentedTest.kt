package com.sysadmin.lasstore.ui.catalog

import android.content.Context
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ApkSignatureScheme
import com.sysadmin.lasstore.test.PrivateScreenshotStore
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PublisherTrustRecoveryDialogInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val screenshots = PrivateScreenshotStore(context)

    @After
    fun cleanup() {
        screenshots.cleanup()
    }

    @Test
    fun recoveryRequiresBothStagesAndRendersAllTrustEvidence() {
        var confirmations = 0
        composeRule.setContent {
            LocalAndroidStoreTheme {
                PublisherTrustRecoveryDialog(
                    details = details(),
                    onDismiss = {},
                    onConfirm = { typedApplicationId, independentlyVerified ->
                        if (typedApplicationId == PACKAGE_NAME && independentlyVerified) {
                            confirmations += 1
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Publisher trust details").assertExists()
        composeRule.onNodeWithText("Personal · owner/repo").assertExists()
        composeRule.onAllNodesWithText(OLD_SIGNER, useUnmergedTree = true).assertCountEquals(2)
        composeRule.onAllNodesWithText(NEW_SIGNER, useUnmergedTree = true).assertCountEquals(1)
        composeRule.onNodeWithText("2. $NEW_SIGNER", substring = true).assertExists()
        composeRule.onNodeWithText("Continue").assertIsNotEnabled()
        saveScreenshot("publisher-trust-details.png")

        composeRule.onNodeWithText("Type the exact package id").performTextInput(PACKAGE_NAME)
        composeRule.onNodeWithText("Continue").assertIsEnabled().performClick()

        composeRule.onNodeWithText("Final trust replacement").assertExists()
        composeRule.onNodeWithText("Replace publisher pin").assertIsNotEnabled()
        composeRule.onNodeWithText(
            "I independently verified this new publisher fingerprint",
            substring = true,
        ).performClick()
        composeRule.onNodeWithText("Replace publisher pin").assertIsEnabled()
        saveScreenshot("publisher-trust-final.png")
        composeRule.onNodeWithText("Replace publisher pin").performClick()

        assertEquals(1, confirmations)
    }

    private fun saveScreenshot(name: String) {
        val image = composeRule.onNodeWithTag("publisherTrustRecoveryDialog")
            .captureToImage()
            .asAndroidBitmap()
        screenshots.save(name, image)
    }

    private fun details() = PublisherTrustDetails(
        source = "Personal · owner/repo",
        installedSignerSha256 = OLD_SIGNER,
        storedPinSha256 = OLD_SIGNER,
        downloadedMetadata = ApkMetadata(
            applicationId = PACKAGE_NAME,
            versionName = "2.0",
            versionCode = 2,
            label = "Example",
            signingSha256 = NEW_SIGNER,
            lineageSha256 = listOf(LINEAGE_SIGNER, NEW_SIGNER),
            verifiedSignatureSchemes = setOf(ApkSignatureScheme.V3),
        ),
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val LINEAGE_SIGNER = "01".repeat(32)
        val OLD_SIGNER = "12".repeat(32)
        val NEW_SIGNER = "34".repeat(32)
    }
}
