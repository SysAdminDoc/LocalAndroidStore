package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LoggerLegacyReadTest {
    @Test
    fun oversizedMalformedDiagnosticsAndCrashLogsKeepOnlyAMarkedTail() {
        val directory = Files.createTempDirectory("las-logger-legacy").toFile()
        writeSparseLegacyFile(directory.resolve("diagnostics.log"), "diagnostics tail")
        writeSparseLegacyFile(directory.resolve("crash.log"), "crash tail")

        val logger = Logger(
            ApplicationProvider.getApplicationContext<Context>(),
            logDirectory = directory,
        )

        val diagnostics = logger.entries.value.single()
        val crash = logger.crashEntries.value.single()
        assertEquals(LogLevel.Info, diagnostics.level)
        assertEquals(LogLevel.Error, crash.level)
        assertTrue(diagnostics.message.contains("diagnostics tail"))
        assertTrue(crash.message.contains("crash tail"))
        assertTrue(diagnostics.message.contains("[TRUNCATED:"))
        assertTrue(crash.message.contains("[TRUNCATED:"))
        assertTrue(diagnostics.message.length <= Logger.MAX_LEGACY_MESSAGE_CHARS)
        assertTrue(crash.message.length <= Logger.MAX_LEGACY_MESSAGE_CHARS)
    }

    private fun writeSparseLegacyFile(file: File, tail: String) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(100L * 1024L * 1024L)
            output.seek(output.length() - tail.length)
            output.write(tail.toByteArray())
        }
    }
}
