package com.sysadmin.lasstore.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSigningBlockReaderTest {
    @Test
    fun readsSigningPairsBeforeZipCentralDirectory() {
        val apk = temporaryFile()
        try {
            writeSyntheticApkSigningBlock(apk)

            val report = ApkSigningBlockReader.read(apk)

            assertTrue(report.present)
            assertEquals(1, report.entries.size)
            assertEquals(0x7109871aL, report.entries.single().id)
            assertEquals("APK Signature Scheme v2", report.entries.single().label)
            assertEquals(3L, report.entries.single().valueSizeBytes)
        } finally {
            apk.delete()
        }
    }

    @Test
    fun treatsUnsignedZipAsAbsentSigningBlock() {
        val apk = temporaryFile()
        try {
            java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("AndroidManifest.xml"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }

            val report = ApkSigningBlockReader.read(apk)

            assertFalse(report.present)
            assertTrue(report.warning.orEmpty().isNotBlank())
        } finally {
            apk.delete()
        }
    }

    private fun temporaryFile(): File = Files.createTempFile("las-signing-block-", ".apk").toFile()

    private fun writeSyntheticApkSigningBlock(file: File) {
        val pairValue = byteArrayOf(0x01, 0x02, 0x03)
        val pairLength = 4L + pairValue.size
        val blockSize = 8L + pairLength + 24L
        val prefix = ByteArray(10)
        val centralDirectoryOffset = prefix.size + 8L + blockSize
        val block = ByteArrayOutputStream().apply {
            writeLongLe(blockSize)
            writeLongLe(pairLength)
            writeIntLe(0x7109871aL)
            write(pairValue)
            writeLongLe(blockSize)
            write(byteArrayOf(
                0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
                0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32,
            ))
        }.toByteArray()
        val eocd = ByteArrayOutputStream().apply {
            writeIntLe(0x06054b50L)
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(0)
            writeShortLe(0)
            writeIntLe(0)
            writeIntLe(centralDirectoryOffset)
            writeShortLe(0)
        }.toByteArray()
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.write(prefix)
            output.write(block)
            output.write(eocd)
        }
    }
}

private fun ByteArrayOutputStream.writeShortLe(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun ByteArrayOutputStream.writeIntLe(value: Long) {
    repeat(4) { index -> write(((value ushr (index * 8)) and 0xff).toInt()) }
}

private fun ByteArrayOutputStream.writeLongLe(value: Long) {
    repeat(8) { index -> write(((value ushr (index * 8)) and 0xff).toInt()) }
}
