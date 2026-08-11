package com.sysadmin.lasstore.ui.log

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.data.LogEntry
import com.sysadmin.lasstore.data.LogLevel
import com.sysadmin.lasstore.data.Logger
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DiagnosticEntryKeyTest {
    @Test
    fun identicalEventsHaveDistinctStableKeysAcrossReloads() {
        val directory = Files.createTempDirectory("las-log-identity").toFile()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val logger = Logger(context, directory)

        logger.info("same-tag", "same-message")
        logger.info("same-tag", "same-message")
        val firstIds = logger.entries.value.map { it.id }
        val reloadedIds = Logger(context, directory).entries.value.map { it.id }

        assertEquals(2, firstIds.size)
        assertEquals(2, firstIds.toSet().size)
        assertEquals(firstIds, reloadedIds)
        assertNotEquals(
            diagnosticEntryKey("Diagnostics", LogEntry(1L, LogLevel.Info, "same", "same", "a")),
            diagnosticEntryKey("Diagnostics", LogEntry(1L, LogLevel.Info, "same", "same", "b")),
        )
        assertTrue(firstIds.all(String::isNotBlank))
    }
}
