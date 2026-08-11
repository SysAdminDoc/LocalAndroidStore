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

    @Test
    fun redactsCredentialsBeforeEntryBoundaryTruncation() {
        val bearerToken = "z".repeat(32)
        val githubPat = "github_pat_" + "A".repeat(30)
        val classicPat = "ghp_" + "B".repeat(36)
        val maxChars = 96
        fun assertBoundaryRedacted(line: String, vararg forbidden: String) {
            val redacted = SupportRedactor.redact(
                "x".repeat(40) + " " + line + " " + "y".repeat(80),
                maxChars = maxChars,
            )
            forbidden.forEach {
                assertFalse(
                    "line=$line forbidden=$it redacted=$redacted",
                    redacted.contains(it),
                )
            }
            assertTrue(redacted.contains("[REDACTED]"))
            assertTrue(redacted.endsWith("[TRUNCATED]"))
        }

        assertBoundaryRedacted("Bearer $bearerToken", "Bearer ${bearerToken.take(8)}")
        assertBoundaryRedacted("token=$classicPat", classicPat.take(8))
        assertBoundaryRedacted(
            "https://example.invalid/?access_token=$githubPat&safe=yes",
            githubPat.take(12),
        )
        assertBoundaryRedacted(
            "signing_secret=${"p".repeat(80)}",
            "p".repeat(8),
        )
    }
}
