package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun cumulativeNotesStopAtTheInstalledVersion() {
        val notes = listOf(
            note(3),
            note(2),
            note(1),
        )

        assertEquals(listOf(3L, 2L), releaseNotesSinceInstalled(notes, 1L, null)
            .map(ReleaseNote::versionCode))
    }

    @Test
    fun cumulativeNotesUsePublishedVersionCodeWhenInstalledReleaseIsNotInHistory() {
        val notes = listOf(note(8), note(7), note(6))

        assertEquals(listOf(8L, 7L), releaseNotesSinceInstalled(notes, 6L, null)
            .map(ReleaseNote::versionCode))
    }

    @Test
    fun whatsNewRejectsUnsafeOrOversizedText() {
        assertEquals("- fix", validateWhatsNew("  - fix  "))
        assertTrue(runCatching { validateWhatsNew("x".repeat(16 * 1024 + 1)) }.isFailure)
        assertTrue(runCatching { validateWhatsNew("bad\u0000text") }.isFailure)
    }

    private fun note(versionCode: Long) = ReleaseNote(
        versionName = versionCode.toString(),
        versionCode = versionCode,
        body = "- change $versionCode",
    )
}
