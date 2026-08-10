package com.sysadmin.lasstore.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportRedactorTest {
    @Test
    fun removesHeadersTokensUrlCredentialsAndNamedSecrets() {
        val githubPat = "github_pat_" + "A".repeat(30)
        val classicPat = "ghp_" + "B".repeat(36)
        val input = """
            Authorization: Bearer $githubPat
            token=$classicPat
            signing_secret: private-material
            https://user:$classicPat@example.invalid/path?access_token=$githubPat&safe=yes
        """.trimIndent()

        val redacted = SupportRedactor.redact(input)

        assertFalse(redacted.contains(githubPat))
        assertFalse(redacted.contains(classicPat))
        assertFalse(redacted.contains("private-material"))
        assertFalse(redacted.contains("user:"))
        assertTrue(redacted.contains("Authorization: [REDACTED]"))
        assertTrue(redacted.contains("signing_secret: [REDACTED]"))
        assertTrue(redacted.contains("safe=yes"))
    }

    @Test
    fun boundsSingleEntryBeforeWritingOrExporting() {
        val redacted = SupportRedactor.redact("x".repeat(40 * 1024))

        assertTrue(redacted.length < 33 * 1024)
        assertTrue(redacted.endsWith("[TRUNCATED]"))
    }
}
