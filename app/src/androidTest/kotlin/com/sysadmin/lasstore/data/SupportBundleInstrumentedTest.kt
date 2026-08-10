package com.sysadmin.lasstore.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.os.BundleCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.ZipFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SupportBundleInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logDir by lazy { File(context.filesDir, "logs") }
    private val supportDir by lazy { File(context.cacheDir, "support") }
    private val unrelatedFile by lazy {
        File(context.filesDir, "unrelated-installed-apps.txt")
    }

    @Before
    @After
    fun cleanup() {
        LOG_FILES.forEach { File(logDir, it).delete() }
        supportDir.listFiles()?.forEach(File::delete)
        supportDir.delete()
        unrelatedFile.delete()
    }

    @Test
    fun loggerStreamsSurviveRecreationAndClearIndependently() {
        val logger = Logger(context)
        logger.clearDiagnostics()
        logger.clearCrashEvidence()
        logger.info("Catalog", "token=$SECRET")
        logger.error("Installer", "Authorization: Bearer $SECRET")

        val recreated = Logger(context)
        assertEquals(2, recreated.entries.value.size)
        assertEquals(1, recreated.crashEntries.value.size)
        assertFalse(recreated.entries.value.joinToString().contains(SECRET))

        recreated.clearDiagnostics()
        assertTrue(Logger(context).entries.value.isEmpty())
        assertEquals(1, Logger(context).crashEntries.value.size)

        recreated.clearCrashEvidence()
        assertTrue(Logger(context).crashEntries.value.isEmpty())
    }

    @Test
    fun legacyCrashTextRemainsVisibleAfterUpgrade() {
        logDir.mkdirs()
        File(logDir, "crash.log").writeText("legacy crash stack")

        val entries = Logger(context).crashEntries.value

        assertEquals(1, entries.size)
        assertEquals(LogLevel.Error, entries.single().level)
        assertTrue(entries.single().message.contains("legacy crash stack"))
    }

    @Test
    fun supportArchiveIsBoundedRedactedAndReadsOnlyAllowlistedEvidence() {
        logDir.mkdirs()
        File(logDir, "diagnostics.log").writeText(
            "Authorization: Bearer $SECRET\n" +
                "url=https://user:$SECRET@example.invalid/release.apk\n",
        )
        File(logDir, "install.log").writeText(
            """{"message":"https://example.invalid/?access_token=$SECRET"}""",
        )
        File(logDir, "crash.log").writeText("signing_secret=$SIGNING_SECRET")
        unrelatedFile.writeText(UNRELATED_PACKAGE)

        val exporter = SupportBundleExporter(context)
        val bundle = exporter.create()
        val entries = linkedMapOf<String, String>()
        ZipFile(bundle).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                entries[entry.name] = zip.getInputStream(entry).bufferedReader().readText()
            }
        }
        val combined = entries.values.joinToString("\n")

        assertEquals(
            setOf("metadata.txt", "diagnostics.log", "install-audit.jsonl", "crash.log"),
            entries.keys,
        )
        assertTrue(bundle.length() < 1024 * 1024)
        assertTrue(entries.getValue("metadata.txt").contains("android_api="))
        assertFalse(combined.contains(SECRET))
        assertFalse(combined.contains(SIGNING_SECRET))
        assertFalse(combined.contains(UNRELATED_PACKAGE))
        assertFalse(combined.contains("user:$SECRET@"))
        assertTrue(combined.contains("[REDACTED]"))

        val share = exporter.shareIntent(bundle)
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("application/zip", share.type)
        assertNotNull(
            share.extras?.let {
                BundleCompat.getParcelable(it, Intent.EXTRA_STREAM, Uri::class.java)
            },
        )
        assertTrue(share.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private companion object {
        val SECRET = "github_pat_" + "A".repeat(30)
        const val SIGNING_SECRET = "not-a-real-private-key"
        const val UNRELATED_PACKAGE = "com.private.unrelated"
        val LOG_FILES = listOf(
            "diagnostics.log",
            "diagnostics.log.1",
            "install.log",
            "install.log.1",
            "crash.log",
            "crash.log.1",
        )
    }
}
