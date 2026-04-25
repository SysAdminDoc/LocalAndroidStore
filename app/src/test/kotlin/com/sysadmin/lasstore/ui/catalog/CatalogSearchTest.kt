package com.sysadmin.lasstore.ui.catalog

import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogSearchTest {
    @Test
    fun blankQueryKeepsOriginalOrder() {
        val cards = listOf(card("Astra Deck"), card("Nova Cut"))

        assertEquals(cards, filterCards(cards, "   "))
    }

    @Test
    fun matchesRepoDescriptionTagAndPackage() {
        val deck = card(
            name = "Astra Deck",
            repo = "Astra-Deck",
            description = "Browser extension companion",
            tagName = "v2.3.0",
            applicationId = "com.sysadmin.astradeck",
        )
        val images = card(
            name = "Images",
            repo = "Images",
            description = "Photo utility",
            tagName = "v1.0.0",
        )

        assertEquals(listOf(deck), filterCards(listOf(deck, images), "browser companion"))
        assertEquals(listOf(deck), filterCards(listOf(deck, images), "v2.3"))
        assertEquals(listOf(deck), filterCards(listOf(deck, images), "sysadmin astradeck"))
    }

    @Test
    fun fuzzySubsequenceMatchesCompactNames() {
        val localStore = card(name = "LocalAndroidStore", repo = "LocalAndroidStore")
        val images = card(name = "Images", repo = "Images")

        assertEquals(listOf(localStore), filterCards(listOf(images, localStore), "las"))
    }

    @Test
    fun higherRelevanceBeatsHigherStars() {
        val exact = card(name = "Deck", repo = "deck", stars = 1)
        val partial = card(name = "Astra Deck", repo = "Astra-Deck", stars = 100)

        assertEquals(listOf(exact, partial), filterCards(listOf(partial, exact), "deck"))
    }

    private fun card(
        name: String,
        repo: String = name,
        description: String? = null,
        tagName: String = "v1.0.0",
        applicationId: String? = null,
        stars: Int = 0,
        status: CardStatus = CardStatus.NotInstalled,
    ): CardState = CardState(
        info = AppInfo(
            owner = "SysAdminDoc",
            repo = repo,
            displayName = name,
            description = description,
            stars = stars,
            htmlUrl = "https://github.com/SysAdminDoc/$repo",
            tagName = tagName,
            versionName = tagName.removePrefix("v"),
            versionCode = null,
            applicationId = applicationId,
            asset = GhAsset(
                name = "$repo.apk",
                browserDownloadUrl = "https://example.com/$repo.apk",
            ),
            publishedAt = null,
            prerelease = false,
        ),
        status = status,
    )
}
