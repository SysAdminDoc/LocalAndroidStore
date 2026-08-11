package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExodusTrackerScannerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun findsPackagedExodusSignaturesInDexEntries() {
        val apk = Files.createTempFile("las-tracker-scan-", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write("prefix com/google/android/gms/ads suffix".toByteArray())
                zip.closeEntry()
            }

            val report = ExodusTrackerScanner(context).scan(apk)

            assertEquals("exodus-snapshot-2026-08", report.databaseVersion)
            assertEquals(listOf("Google AdMob"), report.findings.map(TrackerFinding::name))
            assertFalse(report.truncated)
            assertTrue(report.scannedBytes > 0L)
        } finally {
            apk.delete()
        }
    }
}
