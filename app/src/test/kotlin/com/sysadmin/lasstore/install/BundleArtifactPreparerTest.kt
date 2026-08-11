package com.sysadmin.lasstore.install

import com.sysadmin.lasstore.data.ApkInspector
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BundleArtifactPreparerTest {
    @Test
    fun rejectsAnArchiveWithoutApkEntriesAndRemovesStaging() {
        val source = File.createTempFile("las-invalid", ".apks")
        val staging = File(source.parentFile, "${source.name}.splits")
        ZipOutputStream(FileOutputStream(source)).use { zip ->
            zip.putNextEntry(ZipEntry("metadata.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }

        try {
            val context = RuntimeEnvironment.getApplication()
            val preparer = BundleArtifactPreparer(
                context,
                ApkInspector(context),
            )
            val failure = runCatching {
                preparer.prepare(source, staging)
            }.exceptionOrNull()

            assertTrue(failure is InstallArtifactException)
            assertFalse(staging.exists())
        } finally {
            source.delete()
            staging.deleteRecursively()
        }
    }
}
