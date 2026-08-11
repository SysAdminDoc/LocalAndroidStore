package com.sysadmin.lasstore.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkManifestReaderTest {
    @Test
    fun decodesBinaryManifestTagsNamespacesAndTypedAttributes() {
        val apk = Files.createTempFile("las-manifest-", ".apk").toFile()
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zip.write(binaryManifest())
                zip.closeEntry()
            }

            val xml = ApkManifestReader.read(apk)

            assertTrue(xml, xml.contains("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\""))
            assertTrue(xml, xml.contains("package=\"com.example\""))
            assertTrue(xml, xml.contains("android:minSdkVersion=\"26\""))
            assertTrue(xml, xml.contains("</uses-sdk>"))
        } finally {
            apk.delete()
        }
    }

    private fun binaryManifest(): ByteArray {
        val strings = listOf(
            "android",
            "http://schemas.android.com/apk/res/android",
            "manifest",
            "package",
            "com.example",
            "uses-sdk",
            "minSdkVersion",
            "26",
        )
        val chunks = listOf(
            stringPool(strings),
            namespaceChunk(start = true, prefix = 0, uri = 1),
            startTag(name = 2, attributes = listOf(attribute(namespace = -1, name = 3, raw = 4, data = 4))),
            startTag(name = 5, attributes = listOf(attribute(namespace = 1, name = 6, raw = 7, data = 7))),
            endTag(name = 5),
            endTag(name = 2),
            namespaceChunk(start = false, prefix = 0, uri = 1),
        )
        val body = chunks.fold(ByteArrayOutputStream()) { output, chunk ->
            output.write(chunk)
            output
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeShortLeManifest(0x0003)
            writeShortLeManifest(8)
            writeIntLeManifest((8 + body.size).toLong())
            write(body)
        }.toByteArray()
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        strings.forEach { value ->
            offsets += data.size()
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.write(bytes.size)
            data.write(value.length)
            data.write(bytes)
            data.write(0)
        }
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        return ByteArrayOutputStream().apply {
            writeShortLeManifest(0x0001)
            writeShortLeManifest(headerSize)
            writeIntLeManifest((stringsStart + data.size()).toLong())
            writeIntLeManifest(strings.size.toLong())
            writeIntLeManifest(0)
            writeIntLeManifest(0x100)
            writeIntLeManifest(stringsStart.toLong())
            writeIntLeManifest(0)
            offsets.forEach { writeIntLeManifest(it.toLong()) }
            write(data.toByteArray())
        }.toByteArray()
    }

    private fun namespaceChunk(start: Boolean, prefix: Int, uri: Int): ByteArray =
        ByteArrayOutputStream().apply {
            writeShortLeManifest(if (start) 0x0100 else 0x0101)
            writeShortLeManifest(16)
            writeIntLeManifest(24)
            writeIntLeManifest(1)
            writeIntLeManifest(0xffffffffL)
            writeIntLeManifest(prefix.toLong())
            writeIntLeManifest(uri.toLong())
        }.toByteArray()

    private fun startTag(name: Int, attributes: List<ByteArray>): ByteArray =
        ByteArrayOutputStream().apply {
            writeShortLeManifest(0x0102)
            writeShortLeManifest(16)
            writeIntLeManifest((36 + attributes.sumOf { it.size }).toLong())
            writeIntLeManifest(1)
            writeIntLeManifest(0xffffffffL)
            writeIntLeManifest(0xffffffffL)
            writeIntLeManifest(name.toLong())
            writeShortLeManifest(20)
            writeShortLeManifest(20)
            writeShortLeManifest(attributes.size)
            writeShortLeManifest(0)
            writeShortLeManifest(0)
            writeShortLeManifest(0)
            attributes.forEach(::write)
        }.toByteArray()

    private fun endTag(name: Int): ByteArray =
        ByteArrayOutputStream().apply {
            writeShortLeManifest(0x0103)
            writeShortLeManifest(16)
            writeIntLeManifest(24)
            writeIntLeManifest(1)
            writeIntLeManifest(0xffffffffL)
            writeIntLeManifest(0xffffffffL)
            writeIntLeManifest(name.toLong())
        }.toByteArray()

    private fun attribute(namespace: Int, name: Int, raw: Int, data: Int): ByteArray =
        ByteArrayOutputStream().apply {
            writeIntLeManifest(namespace.toLong())
            writeIntLeManifest(name.toLong())
            writeIntLeManifest(raw.toLong())
            writeShortLeManifest(8)
            write(0)
            write(0x03)
            writeIntLeManifest(data.toLong())
        }.toByteArray()
}

private fun ByteArrayOutputStream.writeShortLeManifest(value: Int) {
    write(value and 0xff)
    write((value ushr 8) and 0xff)
}

private fun ByteArrayOutputStream.writeIntLeManifest(value: Long) {
    repeat(4) { index -> write(((value ushr (index * 8)) and 0xff).toInt()) }
}
