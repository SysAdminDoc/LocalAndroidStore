package com.sysadmin.lasstore.data

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ApkFileDigestsTest {
    @Test
    fun capturesWholeApkAndRawManifestDigests() {
        val first = temporaryApk("first-manifest")
        val second = temporaryApk("second-manifest")

        val firstDigests = digestApkFile(first)
        val secondDigests = digestApkFile(second)

        assertNotNull(firstDigests.manifestSha256)
        assertNotNull(secondDigests.manifestSha256)
        assertNotEquals(firstDigests.apkSha256, secondDigests.apkSha256)
        assertNotEquals(firstDigests.manifestSha256, secondDigests.manifestSha256)

        first.delete()
        second.delete()
    }

    private fun temporaryApk(manifest: String): File =
        Files.createTempFile("las-digest", ".apk").toFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(manifest.toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
}
