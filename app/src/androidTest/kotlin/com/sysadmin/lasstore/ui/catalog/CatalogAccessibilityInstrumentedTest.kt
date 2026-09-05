package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CatalogAccessibilityInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun primaryAndOverflowActionsExposeTalkBackSemantics() {
        renderCard()

        composeRule.onNodeWithText("Example").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Status: Update").assertIsDisplayed()
        composeRule.onNode(hasText("Update") and hasClickAction())
            .assertIsEnabled()
            .assertHasClickAction()
        composeRule.onNodeWithContentDescription("More actions for Example")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun cardBodyExposesDpadPrimaryAction() {
        var invoked = false
        renderCard(onPrimaryAction = { invoked = true })

        composeRule.onNodeWithContentDescription(
            "Example. Personal · owner/repo. Press select to run the primary card action.",
        )
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertTrue(invoked) }
    }

    @Test
    fun cardRemainsOperableAtTwoHundredPercentFontScale() {
        renderCard(fontScale = 2f)

        composeRule.onNodeWithText("Example").assertIsDisplayed()
        composeRule.onNode(hasText("Update") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun brandWrapsAndRefreshRemainsReachableAtTwoHundredPercentRtl() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, 2f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                LocalAndroidStoreTheme {
                    Column(modifier = Modifier.width(180.dp)) {
                        CatalogHero(refreshing = false, onRefresh = {})
                    }
                }
            }
        }

        composeRule.onNodeWithText("LocalAndroidStore", substring = true)
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Refresh catalog")
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun rightToLeftLayoutKeepsPrimaryActionsReachable() {
        renderCard(layoutDirection = LayoutDirection.Rtl)

        composeRule.onNodeWithContentDescription("More actions for Example")
            .assertIsDisplayed()
        composeRule.onNode(hasText("Update") and hasClickAction())
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun trustRecoveryCannotExposeEnabledOneTapReplacement() {
        val details = PublisherTrustDetails(
            source = "Personal · owner/repo",
            installedSignerSha256 = OLD_SIGNER,
            storedPinSha256 = OLD_SIGNER,
            downloadedMetadata = com.sysadmin.lasstore.data.ApkMetadata(
                applicationId = "com.example.app",
                versionName = "2",
                versionCode = 2,
                label = "Example",
                signingSha256 = NEW_SIGNER,
                verifiedSignatureSchemes = setOf(
                    com.sysadmin.lasstore.data.ApkSignatureScheme.V2,
                ),
            ),
        )
        renderCard(
            state = cardState().copy(
                status = CardStatus.SignatureMismatch,
                publisherTrustDetails = details,
            ),
        )

        composeRule.onNodeWithText("Review trust")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNodeWithText("Replace publisher pin")
            .assertDoesNotExist()
    }

    @Test
    fun installFailureIsExposedAsOneDeduplicatedLiveAnnouncement() {
        var state by mutableStateOf(
            cardState().copy(
                status = CardStatus.Working,
                message = "Installing…",
            ),
        )
        composeRule.setContent {
            LocalAndroidStoreTheme {
                ReleaseCard(
                    state = state,
                    onInstall = {},
                    onUpdate = {},
                    onQueueUpdate = {},
                    onCancelQueuedUpdate = {},
                    onUninstall = {},
                    onOpen = {},
                    onRepo = {},
                    onCancel = {},
                    onProceedPermissions = {},
                    onCancelPermissions = {},
                    onIgnore = {},
                    onSaveApk = {},
                    onReplacePublisherPin = { _, _ -> },
                    onSelectAsset = {},
                )
            }
        }
        composeRule.runOnIdle {
            state = state.copy(
                status = CardStatus.Error,
                message = "Network unavailable",
            )
        }

        composeRule.onNodeWithContentDescription(
            "Example: installation failed. Network unavailable Review the error and retry.",
            useUnmergedTree = true,
        ).assertExists()
    }

    private fun renderCard(
        state: CardState = cardState(),
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        onPrimaryAction: () -> Unit = {},
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides layoutDirection,
            ) {
                LocalAndroidStoreTheme {
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        ReleaseCard(
                            state = state,
                            onInstall = {},
                            onUpdate = {},
                            onPrimaryAction = onPrimaryAction,
                            onQueueUpdate = {},
                            onCancelQueuedUpdate = {},
                            onUninstall = {},
                            onOpen = {},
                            onRepo = {},
                            onCancel = {},
                            onProceedPermissions = {},
                            onCancelPermissions = {},
                            onIgnore = {},
                            onSaveApk = {},
                            onReplacePublisherPin = { _, _ -> },
                            onSelectAsset = {},
                        )
                    }
                }
            }
        }
    }

    private fun cardState() = CardState(
        info = AppInfo(
            owner = "owner",
            repo = "repo",
            sourceKey = "personal",
            sourceLabel = "Personal",
            displayName = "Example",
            description = "A deliberately long description that exercises wrapping.",
            stars = 42,
            htmlUrl = "https://github.com/owner/repo",
            tagName = "v2.0.0",
            versionName = "2.0.0",
            versionCode = 2,
            applicationId = "com.example.app",
            asset = GhAsset(
                id = 2,
                name = "example-universal.apk",
                browserDownloadUrl = "https://example.invalid/example.apk",
                size = 100,
            ),
            publishedAt = null,
            prerelease = false,
        ),
        status = CardStatus.UpdateAvailable,
        installedVersion = "1.0.0",
        installedVersionCode = 1,
    )

    private companion object {
        val OLD_SIGNER = "12".repeat(32)
        val NEW_SIGNER = "34".repeat(32)
    }
}
