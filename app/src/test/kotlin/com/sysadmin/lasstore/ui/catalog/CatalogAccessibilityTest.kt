package com.sysadmin.lasstore.ui.catalog

import androidx.compose.ui.semantics.LiveRegionMode
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.install.QueuedUpdatePhase
import com.sysadmin.lasstore.install.QueuedUpdateStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogAccessibilityTest {
    @Test
    fun downloadProgressCollapsesToOneStableAnnouncement() {
        val first = card(CardStatus.Working, "Downloading… 1%")
        val later = card(CardStatus.Working, "Downloading… 78%")

        assertEquals(
            cardAccessibilityAnnouncement(first),
            cardAccessibilityAnnouncement(later),
        )
    }

    @Test
    fun foregroundSuccessAndFailureIncludeTheNextAction() {
        val success = card(CardStatus.Installed)
        val failure = card(CardStatus.Error, "Network unavailable")

        assertTrue(cardAccessibilityAnnouncement(success)!!.text.contains("Example"))
        assertTrue(cardAccessibilityAnnouncement(success)!!.text.contains("successfully"))
        assertTrue(cardAccessibilityAnnouncement(failure)!!.text.contains("retry"))
    }

    @Test
    fun publisherMismatchUsesAssertiveAnnouncement() {
        val announcement = cardAccessibilityAnnouncement(
            card(CardStatus.SignatureMismatch),
        )

        assertEquals(LiveRegionMode.Assertive, announcement!!.liveRegion)
    }

    @Test
    fun queuedTerminalStatesAreAnnouncedWithTheAppName() {
        val state = card(CardStatus.UpdateAvailable).copy(
            queuedUpdateStatus = QueuedUpdateStatus(
                workName = "update",
                sourceKey = "source",
                owner = "owner",
                repo = "repo",
                displayName = "Example",
                phase = QueuedUpdatePhase.Installed,
                attempt = 1,
                maxAttempts = 3,
                message = "Background update installed.",
                updatedAtEpochMillis = 1L,
            ),
        )

        assertTrue(
            cardAccessibilityAnnouncement(state)!!.text
                .startsWith("Example: background update installed"),
        )
    }

    private fun card(status: CardStatus, message: String? = null) = CardState(
        info = AppInfo(
            owner = "owner",
            repo = "repo",
            sourceKey = "source",
            sourceLabel = "Source",
            displayName = "Example",
            description = "Description",
            stars = 1,
            htmlUrl = "https://github.com/owner/repo",
            tagName = "v1.0.0",
            versionName = "1.0.0",
            versionCode = 1L,
            applicationId = "com.example.app",
            asset = GhAsset(
                name = "example.apk",
                browserDownloadUrl = "https://example.invalid/example.apk",
            ),
            publishedAt = null,
            prerelease = false,
        ),
        status = status,
        message = message,
    )
}
