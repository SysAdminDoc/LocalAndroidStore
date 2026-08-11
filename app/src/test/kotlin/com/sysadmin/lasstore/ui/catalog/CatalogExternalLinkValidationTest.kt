package com.sysadmin.lasstore.ui.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CatalogExternalLinkValidationTest {
    @Test
    fun onlyHttpsGitHubRepositoryLinksAreAccepted() {
        assertEquals(
            "github.com",
            validatedGitHubRepositoryUri("https://github.com/SysAdminDoc/LocalAndroidStore")?.host,
        )
        assertEquals(
            "www.github.com",
            validatedGitHubRepositoryUri("https://www.github.com/SysAdminDoc/LocalAndroidStore")?.host,
        )
    }

    @Test
    fun malformedAndNonGitHubLinksAreRejected() {
        listOf(
            "http://github.com/SysAdminDoc/LocalAndroidStore",
            "intent://github.com/SysAdminDoc/LocalAndroidStore",
            "https://github.com.evil.example/SysAdminDoc/LocalAndroidStore",
            "https://evil.example/SysAdminDoc/LocalAndroidStore",
            "https://attacker@github.com/SysAdminDoc/LocalAndroidStore",
            "https://github.com/SysAdminDoc",
            "not a url",
        ).forEach { rawUrl ->
            assertNull(rawUrl, validatedGitHubRepositoryUri(rawUrl))
        }
    }
}
