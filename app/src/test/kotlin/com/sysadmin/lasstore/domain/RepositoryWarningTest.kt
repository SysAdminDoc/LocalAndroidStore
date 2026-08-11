package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RepositoryWarningTest {
    @Test
    fun archivedTakesPriorityOverActivityAge() {
        val warning = repositoryMaintenanceWarning(
            archived = true,
            lastActivityAt = "not-a-date",
            nowEpochMillis = 1_000L,
        )

        assertEquals(RepositoryWarningKind.Archived, warning?.kind)
    }

    @Test
    fun staleActivityIsWarnedAtTwelveMonthsAndRecentActivityIsNot() {
        val now = 2_000_000_000_000L
        val stale = java.time.Instant.ofEpochMilli(
            now - 365L * 24L * 60L * 60L * 1000L,
        ).toString()
        val recent = java.time.Instant.ofEpochMilli(now - 1_000L).toString()

        assertEquals(
            RepositoryWarningKind.Inactive,
            repositoryMaintenanceWarning(false, stale, now)?.kind,
        )
        assertNull(repositoryMaintenanceWarning(false, recent, now))
    }
}
