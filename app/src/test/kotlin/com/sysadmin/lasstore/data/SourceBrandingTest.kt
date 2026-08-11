package com.sysadmin.lasstore.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SourceBrandingTest {
    @Test
    fun brandingUrlRequiresCredentialFreeHttps() {
        assertNotNull(validatedSourceBrandingUrl("https://example.com/source.json"))
        assertNull(validatedSourceBrandingUrl("http://example.com/source.json"))
        assertNull(validatedSourceBrandingUrl("https://user:pass@example.com/source.json"))
        assertNull(validatedSourceBrandingUrl("https://example.com:8443/source.json"))
        assertEquals(
            "Use an HTTPS branding feed URL without credentials.",
            validateSourceBrandingUrl("http://example.com/source.json"),
        )
    }

    @Test
    fun parsesAltStoreCompatibleBrandingFields() {
        val branding = Json.decodeFromString<SourceBranding>(
            """
            {
              "iconURL": "https://example.com/icon.png",
              "headerURL": "https://example.com/header.png",
              "tintColor": "#8839ef",
              "featuredApps": ["com.example.one", "com.example.two"],
              "news": [{"title": "Welcome", "caption": "First release", "url": "https://example.com/news"}]
            }
            """.trimIndent(),
        )

        assertEquals("https://example.com/icon.png", branding.iconUrl)
        assertEquals("#8839ef", branding.tintColor)
        assertEquals(listOf("com.example.one", "com.example.two"), branding.featuredApps)
        assertEquals("Welcome", branding.news.single().title)
        assertEquals("First release", branding.news.single().caption)
    }
}
