package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogTagTest {
    @Test
    fun sourceTagsAreNormalizedAndNamespaced() {
        assertEquals("github:topic:privacy-friendly", githubTopicTag("Privacy Friendly"))
        assertEquals("fdroid:category:internet", fdroidCategoryTag("Internet"))
        assertEquals("GitHub · privacy friendly", catalogTagLabel("github:topic:privacy-friendly"))
    }

    @Test
    fun blankSourceTagsAreIgnored() {
        assertNull(githubTopicTag("---"))
        assertNull(fdroidCategoryTag("  "))
    }
}
