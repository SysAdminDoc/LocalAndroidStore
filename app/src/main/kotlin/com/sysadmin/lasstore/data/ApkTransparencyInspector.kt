package com.sysadmin.lasstore.data

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.ZipFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

data class ApkTransparencyReport(
    val metadata: ApkMetadata,
    val manifestXml: String,
    val signingBlock: ApkSigningBlockReport,
    val trackerScan: TrackerScanReport,
)

data class ApkSigningBlockReport(
    val present: Boolean,
    val blockSizeBytes: Long = 0L,
    val entries: List<ApkSigningBlockEntry> = emptyList(),
    val warning: String? = null,
)

data class ApkSigningBlockEntry(
    val id: Long,
    val label: String,
    val valueSizeBytes: Long,
)

data class TrackerScanReport(
    val databaseVersion: String,
    val findings: List<TrackerFinding>,
    val scannedBytes: Long,
    val truncated: Boolean,
)

data class TrackerFinding(
    val name: String,
    val category: String,
    val evidence: String,
)

class ApkTransparencyInspector(private val context: Context) {
    private val apkInspector = ApkInspector(context)
    private val trackerScanner = ExodusTrackerScanner(context)

    fun inspect(apk: File): ApkTransparencyReport {
        val metadata = when (val result = apkInspector.inspectResult(apk)) {
            is ApkInspectionResult.Verified -> result.metadata
            is ApkInspectionResult.Rejected -> throw IOException(result.reason.userMessage)
        }
        return ApkTransparencyReport(
            metadata = metadata,
            manifestXml = ApkManifestReader.read(apk),
            signingBlock = ApkSigningBlockReader.read(apk),
            trackerScan = trackerScanner.scan(apk),
        )
    }
}

/** Decodes Android's binary XML manifest into a readable XML representation without extraction. */
object ApkManifestReader {
    private const val MAX_MANIFEST_BYTES = 2_000_000L

    fun read(apk: File): String {
        require(apk.isFile) { "APK is unavailable" }
        val manifest = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: throw IOException("APK does not contain AndroidManifest.xml")
            if (entry.size > MAX_MANIFEST_BYTES) {
                throw IOException("APK manifest is too large to display")
            }
            zip.getInputStream(entry).use { input ->
                input.readBytes().also { bytes ->
                    if (bytes.size > MAX_MANIFEST_BYTES) {
                        throw IOException("APK manifest is too large to display")
                    }
                }
            }
        }
        return BinaryXmlSerializer(manifest).serialize()
    }

    private class BinaryXmlSerializer(private val bytes: ByteArray) {
        private val strings = mutableListOf<String>()
        private val namespaces = linkedMapOf<String, String>()
        private val pendingNamespaces = linkedMapOf<String, String>()
        private val output = StringBuilder()
        private var depth = 0

        fun serialize(): String {
            requireChunk(0, XML_CHUNK_TYPE, "AndroidManifest.xml")
            val xmlHeaderSize = uShort(2)
            val xmlSize = int(4)
            if (xmlHeaderSize < 8 || xmlSize < xmlHeaderSize || xmlSize > bytes.size) {
                throw IOException("AndroidManifest.xml has an invalid outer chunk")
            }
            output.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
            var offset = xmlHeaderSize
            while (offset + 8 <= xmlSize) {
                val type = uShort(offset)
                val headerSize = uShort(offset + 2)
                val chunkSize = int(offset + 4)
                if (headerSize < 8 || chunkSize < headerSize || offset + chunkSize > xmlSize) {
                    throw IOException("AndroidManifest.xml contains an invalid chunk")
                }
                when (type) {
                    STRING_POOL_CHUNK_TYPE -> readStringPool(offset, headerSize)
                    START_NAMESPACE_CHUNK_TYPE -> readStartNamespace(offset)
                    END_NAMESPACE_CHUNK_TYPE -> readEndNamespace(offset)
                    START_TAG_CHUNK_TYPE -> readStartTag(offset)
                    END_TAG_CHUNK_TYPE -> readEndTag(offset)
                    CDATA_CHUNK_TYPE -> readCdata(offset)
                }
                offset += chunkSize
            }
            if (depth != 0 || strings.isEmpty()) {
                throw IOException("AndroidManifest.xml is incomplete")
            }
            return output.toString().trimEnd()
        }

        private fun readStringPool(offset: Int, headerSize: Int) {
            if (headerSize < STRING_POOL_HEADER_SIZE) {
                throw IOException("AndroidManifest.xml string pool is invalid")
            }
            val stringCount = int(offset + 8)
            val flags = int(offset + 16)
            val stringsStart = int(offset + 20)
            val offsetsStart = offset + headerSize
            val dataStart = offset + stringsStart
            if (
                stringCount < 0 ||
                stringCount > MAX_STRING_COUNT ||
                dataStart < offsetsStart ||
                dataStart > bytes.size
            ) {
                throw IOException("AndroidManifest.xml string pool is invalid")
            }
            repeat(stringCount) { index ->
                val stringOffset = int(offsetsStart + index * 4)
                val position = dataStart + stringOffset
                strings += if ((flags and UTF8_STRING_POOL_FLAG) != 0) {
                    readUtf8(position)
                } else {
                    readUtf16(position)
                }
            }
        }

        private fun readStartNamespace(offset: Int) {
            val prefix = string(int(offset + 16))
            val uri = string(int(offset + 20))
            if (prefix != null && uri != null) {
                namespaces[prefix] = uri
                pendingNamespaces[prefix] = uri
            }
        }

        private fun readEndNamespace(offset: Int) {
            val prefix = string(int(offset + 16))
            if (prefix != null) namespaces.remove(prefix)
        }

        private fun readStartTag(offset: Int) {
            val namespace = string(int(offset + 16))
            val name = string(int(offset + 20)) ?: throw IOException("Manifest tag has no name")
            val attributeStart = uShort(offset + 24)
            val attributeSize = uShort(offset + 26)
            val attributeCount = uShort(offset + 28)
            if (attributeSize < ATTRIBUTE_SIZE || attributeCount < 0) {
                throw IOException("Manifest attributes are invalid")
            }
            val attributesStart = offset + 16 + attributeStart
            if (attributesStart < offset || attributesStart + attributeCount * attributeSize > bytes.size) {
                throw IOException("Manifest attributes are out of bounds")
            }
            indent()
            output.append('<').append(name)
            pendingNamespaces.forEach { (prefix, uri) ->
                output.append(" xmlns")
                if (prefix.isNotBlank()) output.append(':').append(prefix)
                output.append("=\"").append(escape(uri)).append('\"')
            }
            pendingNamespaces.clear()
            repeat(attributeCount) { index ->
                val attributeOffset = attributesStart + index * attributeSize
                val attributeNamespace = string(int(attributeOffset))
                val attributeName = string(int(attributeOffset + 4)) ?: return@repeat
                val rawValue = string(int(attributeOffset + 8))
                val type = uByte(attributeOffset + 15)
                val data = int(attributeOffset + 16)
                val prefix = attributeNamespace?.let(::prefixForNamespace)
                output.append(' ')
                if (!prefix.isNullOrBlank()) output.append(prefix).append(':')
                output.append(attributeName).append("=\"")
                    .append(escape(rawValue ?: formatTypedValue(type, data)))
                    .append('\"')
            }
            output.append(">\n")
            depth++
            if (namespace != null && namespace !in namespaces.values) {
                // The namespace URI is retained for diagnostics even if a malformed APK omitted
                // its START_NAMESPACE event; the attribute still renders without a bad prefix.
                namespaces["ns${namespaces.size}"] = namespace
            }
        }

        private fun readEndTag(offset: Int) {
            depth = (depth - 1).coerceAtLeast(0)
            indent()
            output.append("</").append(string(int(offset + 20)).orEmpty()).append(">\n")
        }

        private fun readCdata(offset: Int) {
            val text = string(int(offset + 24))?.trim().orEmpty()
            if (text.isNotEmpty()) {
                indent()
                output.append(escape(text)).append('\n')
            }
        }

        private fun prefixForNamespace(uri: String): String? =
            namespaces.entries.firstOrNull { it.value == uri }?.key

        private fun formatTypedValue(type: Int, data: Int): String = when (type) {
            TYPE_REFERENCE -> "@0x${data.toString(16)}"
            TYPE_ATTRIBUTE -> "?0x${data.toString(16)}"
            TYPE_FLOAT -> Float.fromBits(data).toString()
            TYPE_DIMENSION -> "0x${data.toString(16)}dp"
            TYPE_FRACTION -> "${(complexToFloat(data) * 100).toInt()}%"
            TYPE_INT_HEX -> "0x${data.toString(16)}"
            TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
            TYPE_INT_COLOR_ARGB8,
            TYPE_INT_COLOR_ARGB4,
            TYPE_INT_COLOR_RGB8,
            TYPE_INT_COLOR_RGB4,
            -> "#${data.toUInt().toString(16).padStart(8, '0')}"
            else -> data.toString()
        }

        private fun complexToFloat(value: Int): Float {
            val mantissa = (value and 0xFFFFFF00.toInt()) shr 8
            val radix = (value shr 4) and 0x3
            val multiplier = when (radix) {
                0 -> 1f / (1 shl 8)
                1 -> 1f / (1 shl 7)
                2 -> 1f / (1 shl 15)
                else -> 1f / (1 shl 23)
            }
            return mantissa * multiplier
        }

        private fun readUtf8(position: Int): String {
            val (_, afterUtf16Length) = readLength8(position)
            val (byteLength, start) = readLength8(afterUtf16Length)
            checkBounds(start, byteLength)
            return String(bytes, start, byteLength, Charsets.UTF_8)
        }

        private fun readUtf16(position: Int): String {
            val (length, afterLength) = readLength16(position)
            checkBounds(afterLength, length * 2)
            return String(bytes, afterLength, length * 2, Charsets.UTF_16LE)
        }

        private fun readLength8(position: Int): Pair<Int, Int> {
            val first = uByte(position)
            return if ((first and 0x80) == 0) {
                first to position + 1
            } else {
                (((first and 0x7f) shl 8) or uByte(position + 1)) to position + 2
            }
        }

        private fun readLength16(position: Int): Pair<Int, Int> {
            val first = uShort(position)
            return if (first == 0xffff) {
                (((uShort(position + 2) and 0x7fff) shl 16) or uShort(position + 4)) to position + 6
            } else {
                first to position + 2
            }
        }

        private fun string(index: Int): String? =
            if (index == NO_INDEX) null else strings.getOrNull(index)

        private fun indent() {
            repeat(depth) { output.append("  ") }
        }

        private fun requireChunk(offset: Int, expectedType: Int, name: String) {
            if (bytes.size < 8 || uShort(offset) != expectedType) {
                throw IOException("$name is not a binary Android XML document")
            }
        }

        private fun checkBounds(offset: Int, length: Int) {
            if (offset < 0 || length < 0 || offset > bytes.size - length) {
                throw IOException("AndroidManifest.xml string is out of bounds")
            }
        }

        private fun uByte(offset: Int): Int {
            checkBounds(offset, 1)
            return bytes[offset].toInt() and 0xff
        }

        private fun uShort(offset: Int): Int {
            checkBounds(offset, 2)
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8)
        }

        private fun int(offset: Int): Int {
            checkBounds(offset, 4)
            return (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)
        }

        private fun escape(value: String): String = buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }

        private companion object {
            const val XML_CHUNK_TYPE = 0x0003
            const val STRING_POOL_CHUNK_TYPE = 0x0001
            const val START_NAMESPACE_CHUNK_TYPE = 0x0100
            const val END_NAMESPACE_CHUNK_TYPE = 0x0101
            const val START_TAG_CHUNK_TYPE = 0x0102
            const val END_TAG_CHUNK_TYPE = 0x0103
            const val CDATA_CHUNK_TYPE = 0x0104
            const val STRING_POOL_HEADER_SIZE = 28
            const val ATTRIBUTE_SIZE = 20
            const val UTF8_STRING_POOL_FLAG = 0x100
            const val NO_INDEX = -1
            const val TYPE_REFERENCE = 0x01
            const val TYPE_ATTRIBUTE = 0x02
            const val TYPE_FLOAT = 0x04
            const val TYPE_DIMENSION = 0x05
            const val TYPE_FRACTION = 0x06
            const val TYPE_INT_HEX = 0x11
            const val TYPE_INT_BOOLEAN = 0x12
            const val TYPE_INT_COLOR_ARGB8 = 0x1c
            const val TYPE_INT_COLOR_RGB8 = 0x1d
            const val TYPE_INT_COLOR_ARGB4 = 0x1e
            const val TYPE_INT_COLOR_RGB4 = 0x1f
            const val MAX_STRING_COUNT = 100_000
        }
    }
}

/** Reads the v2/v3 APK Signing Block immediately before the ZIP central directory. */
object ApkSigningBlockReader {
    private val MAGIC = byteArrayOf(
        0x41, 0x50, 0x4b, 0x20, 0x53, 0x69, 0x67, 0x20,
        0x42, 0x6c, 0x6f, 0x63, 0x6b, 0x20, 0x34, 0x32,
    )

    fun read(apk: File): ApkSigningBlockReport {
        if (!apk.isFile) return ApkSigningBlockReport(false, warning = "APK is unavailable")
        return runCatching { readChecked(apk) }.getOrElse { throwable ->
            ApkSigningBlockReport(
                present = false,
                warning = throwable.message ?: "Signing block could not be parsed",
            )
        }
    }

    private fun readChecked(apk: File): ApkSigningBlockReport =
        RandomAccessFile(apk, "r").use { file ->
            val eocdOffset = findEndOfCentralDirectory(file)
                ?: return ApkSigningBlockReport(false, warning = "ZIP central directory not found")
            val centralDirectoryOffset = readUInt(file, eocdOffset + 16)
            if (centralDirectoryOffset < 24L) {
                return ApkSigningBlockReport(false, warning = "APK signing block is absent")
            }
            val footerOffset = centralDirectoryOffset - 24L
            if (footerOffset < 0L || footerOffset + 24L > file.length()) {
                return ApkSigningBlockReport(false, warning = "APK signing block is absent")
            }
            val blockSize = readLong(file, footerOffset)
            val magic = readBytes(file, footerOffset + 8L, MAGIC.size)
            if (blockSize < 24L || !magic.contentEquals(MAGIC)) {
                return ApkSigningBlockReport(false, warning = "APK signing block is absent")
            }
            val blockOffset = centralDirectoryOffset - blockSize - 8L
            if (blockOffset < 0L || blockOffset + blockSize + 8L > file.length()) {
                return ApkSigningBlockReport(false, warning = "APK signing block bounds are invalid")
            }
            if (readLong(file, blockOffset) != blockSize) {
                return ApkSigningBlockReport(false, warning = "APK signing block size is inconsistent")
            }

            val entries = mutableListOf<ApkSigningBlockEntry>()
            var cursor = blockOffset + 8L
            val pairEnd = centralDirectoryOffset - 24L
            while (cursor < pairEnd) {
                if (pairEnd - cursor < 8L) {
                    return ApkSigningBlockReport(false, warning = "APK signing block pair is truncated")
                }
                val pairLength = readLong(file, cursor)
                if (pairLength < 4L || pairLength > pairEnd - cursor - 8L) {
                    return ApkSigningBlockReport(false, warning = "APK signing block pair length is invalid")
                }
                val id = readUInt(file, cursor + 8L)
                entries += ApkSigningBlockEntry(
                    id = id,
                    label = labelFor(id),
                    valueSizeBytes = pairLength - 4L,
                )
                cursor += 8L + pairLength
            }
            if (cursor != pairEnd) {
                return ApkSigningBlockReport(false, warning = "APK signing block pairs are misaligned")
            }
            ApkSigningBlockReport(
                present = entries.isNotEmpty(),
                blockSizeBytes = blockSize,
                entries = entries,
                warning = null,
            )
        }

    private fun findEndOfCentralDirectory(file: RandomAccessFile): Long? {
        val minimum = 22L
        val start = (file.length() - minimum - 65_535L).coerceAtLeast(0L)
        val end = file.length() - minimum
        for (offset in end downTo start) {
            if (readUInt(file, offset) == ZIP_EOCD_SIGNATURE &&
                readUShort(file, offset + 20L) == (file.length() - offset - 22L).coerceAtLeast(0L)
            ) {
                return offset
            }
        }
        return null
    }

    private fun readBytes(file: RandomAccessFile, offset: Long, length: Int): ByteArray {
        file.seek(offset)
        return ByteArray(length).also(file::readFully)
    }

    private fun readUInt(file: RandomAccessFile, offset: Long): Long =
        readBytes(file, offset, 4).let { bytes ->
            (bytes[0].toLong() and 0xff) or
                ((bytes[1].toLong() and 0xff) shl 8) or
                ((bytes[2].toLong() and 0xff) shl 16) or
                ((bytes[3].toLong() and 0xff) shl 24)
        }

    private fun readLong(file: RandomAccessFile, offset: Long): Long =
        readBytes(file, offset, 8).let { bytes ->
            var value = 0L
            for (index in bytes.indices) {
                value = value or ((bytes[index].toLong() and 0xff) shl (index * 8))
            }
            value
        }

    private fun readUShort(file: RandomAccessFile, offset: Long): Long =
        readBytes(file, offset, 2).let { bytes ->
            (bytes[0].toLong() and 0xff) or
                ((bytes[1].toLong() and 0xff) shl 8)
        }

    private fun labelFor(id: Long): String = when (id) {
        APK_SIGNATURE_SCHEME_V2 -> "APK Signature Scheme v2"
        APK_SIGNATURE_SCHEME_V3 -> "APK Signature Scheme v3"
        APK_SIGNATURE_SCHEME_V31 -> "APK Signature Scheme v3.1"
        SOURCE_STAMP -> "Source Stamp"
        VERITY_PADDING -> "Verity padding"
        else -> "Unknown block 0x${id.toString(16)}"
    }

    private const val ZIP_EOCD_SIGNATURE = 0x06054b50L
    private const val APK_SIGNATURE_SCHEME_V2 = 0x7109871aL
    private const val APK_SIGNATURE_SCHEME_V3 = 0xf05368c0L
    private const val APK_SIGNATURE_SCHEME_V31 = 0x1b93ad61L
    private const val SOURCE_STAMP = 0x6dff800dL
    private const val VERITY_PADDING = 0x42726577L
}

internal class ExodusTrackerScanner(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    fun scan(apk: File): TrackerScanReport {
        val database = loadDatabase()
        val matches = mutableMapOf<String, TrackerFinding>()
        var scannedBytes = 0L
        var truncated = false
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { entry -> entry.name.matches(DEX_ENTRY) }
                .forEach entryLoop@{ entry ->
                    if (scannedBytes >= MAX_SCAN_BYTES) {
                        truncated = true
                        return@entryLoop
                    }
                    val remaining = MAX_SCAN_BYTES - scannedBytes
                    val bytes = readBounded(zip, entry, remaining)
                    scannedBytes += bytes.size
                    if (bytes.size.toLong() >= remaining && entry.size > bytes.size) {
                        truncated = true
                    }
                    database.trackers.forEach trackerLoop@{ tracker ->
                        if (tracker.id in matches) return@trackerLoop
                        val evidence = tracker.patterns.firstOrNull { pattern ->
                            containsAsciiIgnoreCase(bytes, pattern)
                        } ?: return@trackerLoop
                        matches[tracker.id] = TrackerFinding(
                            name = tracker.name,
                            category = tracker.category,
                            evidence = evidence,
                        )
                    }
                }
        }
        return TrackerScanReport(
            databaseVersion = database.version,
            findings = matches.values.sortedBy { it.name.lowercase() },
            scannedBytes = scannedBytes,
            truncated = truncated,
        )
    }

    private fun loadDatabase(): TrackerDatabase {
        val raw = context.assets.open(TRACKER_DATABASE_ASSET).bufferedReader().use { it.readText() }
        return json.decodeFromString(raw)
    }

    private fun readBounded(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        maxBytes: Long,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, MAX_ENTRY_BYTES).toInt())
        val buffer = ByteArray(32 * 1024)
        zip.getInputStream(entry).use { input ->
            while (output.size().toLong() < minOf(maxBytes, MAX_ENTRY_BYTES)) {
                val remaining = minOf(
                    buffer.size.toLong(),
                    minOf(maxBytes, MAX_ENTRY_BYTES) - output.size().toLong(),
                ).toInt()
                val count = input.read(buffer, 0, remaining)
                if (count <= 0) break
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray()
    }

    private fun containsAsciiIgnoreCase(bytes: ByteArray, pattern: String): Boolean {
        val needle = pattern.encodeToByteArray()
        if (needle.isEmpty() || needle.size > bytes.size) return false
        for (start in 0..bytes.size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (asciiLower(bytes[start + index]) != asciiLower(needle[index])) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun asciiLower(value: Byte): Byte =
        if (value in 'A'.code.toByte()..'Z'.code.toByte()) {
            (value + ('a'.code - 'A'.code).toByte()).toByte()
        } else {
            value
        }

    @Serializable
    private data class TrackerDatabase(
        val version: String,
        val trackers: List<TrackerSignature>,
    )

    @Serializable
    private data class TrackerSignature(
        val id: String,
        val name: String,
        val category: String,
        val patterns: List<String>,
    )

    private companion object {
        const val TRACKER_DATABASE_ASSET = "exodus_tracker_signatures.json"
        const val MAX_SCAN_BYTES = 128L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
        val DEX_ENTRY = Regex("classes(\\d*)\\.dex")
    }
}
