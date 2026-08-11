package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.util.zip.ZipFile
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SupportBundleExporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rapidExportsRemainDistinctAndTheFirstZipStaysReadable() {
        val directory = Files.createTempDirectory("las-support-export").toFile()
        val exporter = SupportBundleExporter(context, directory)

        val first = exporter.create()
        val firstBytes = first.readBytes()
        val second = exporter.create()

        assertNotEquals(first.absolutePath, second.absolutePath)
        assertTrue(first.exists())
        assertTrue(second.exists())
        assertTrue(firstBytes.contentEquals(first.readBytes()))
        ZipFile(first).use { zip -> assertNotNull(zip.getEntry("metadata.txt")) }
        ZipFile(second).use { zip -> assertNotNull(zip.getEntry("metadata.txt")) }
    }

    @Test
    fun retentionPrunesOnlyOldKnownBundlesAndKeepsARecentSharedBundle() {
        val directory = Files.createTempDirectory("las-support-retention").toFile()
        val now = System.currentTimeMillis()
        repeat(MAX_RETAINED_SUPPORT_BUNDLES + 4) { index ->
            val old = directory.resolve("las-support-old-$index.zip")
            old.writeText("old-$index")
            old.setLastModified(now - SUPPORT_BUNDLE_SHARE_SAFETY_MILLIS - index * 1_000L)
        }
        val shared = directory.resolve("las-support-shared.zip")
        shared.writeText("shared")
        shared.setLastModified(now)

        val newest = SupportBundleExporter(context, directory).create()
        val bundles = directory.listFiles()
            ?.count { it.isFile && it.name.startsWith("las-support-") && it.extension == "zip" }
            ?: 0

        assertTrue(newest.exists())
        assertTrue(shared.exists())
        assertTrue(shared.readText() == "shared")
        assertTrue(bundles <= MAX_RETAINED_SUPPORT_BUNDLES)
    }
}
