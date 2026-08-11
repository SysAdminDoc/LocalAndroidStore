package com.sysadmin.lasstore.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

enum class LogLevel { Info, Warn, Error }

@Serializable
data class LogEntry(
    val ts: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val id: String = "",
    val highRisk: Boolean = false,
)

class Logger(
    private val context: Context,
    logDirectory: File? = null,
) {
    private val logDir = (logDirectory ?: File(context.filesDir, "logs")).apply { mkdirs() }
    private val diagnosticsFile = File(logDir, "diagnostics.log")
    private val rotatedDiagnosticsFile = File(logDir, "diagnostics.log.1")
    private val crashFile = File(logDir, "crash.log")
    private val rotatedCrashFile = File(logDir, "crash.log.1")
    private val json = Json { ignoreUnknownKeys = true }
    private val fileLock = Any()

    private val _entries = MutableStateFlow(readEntries(rotatedDiagnosticsFile, diagnosticsFile))
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()
    private val _crashEntries = MutableStateFlow(readEntries(rotatedCrashFile, crashFile))
    val crashEntries: StateFlow<List<LogEntry>> = _crashEntries.asStateFlow()

    fun info(tag: String, message: String) = append(LogLevel.Info, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.Warn, tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = throwable?.let { message + "\n" + it.stackTraceToString() } ?: message
        append(LogLevel.Error, tag, full)
        appendCrash(tag, full)
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            ts = System.currentTimeMillis(),
            level = level,
            tag = SupportRedactor.redact(tag),
            message = SupportRedactor.redact(message),
            id = UUID.randomUUID().toString(),
        )
        persist(diagnosticsFile, rotatedDiagnosticsFile, entry)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun clear() = clearDiagnostics()

    fun clearDiagnostics() {
        synchronized(fileLock) {
            diagnosticsFile.delete()
            rotatedDiagnosticsFile.delete()
        }
        _entries.value = emptyList()
    }

    fun clearCrashEvidence() {
        synchronized(fileLock) {
            crashFile.delete()
            rotatedCrashFile.delete()
        }
        _crashEntries.value = emptyList()
    }

    fun installCrashHandler() {
        val previous = AtomicReference(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendCrash("Uncaught/${thread.name}", sw.toString())
            previous.get()?.uncaughtException(thread, throwable)
        }
    }

    private fun appendCrash(tag: String, message: String) {
        val entry = LogEntry(
            ts = System.currentTimeMillis(),
            level = LogLevel.Error,
            tag = SupportRedactor.redact(tag),
            message = SupportRedactor.redact(message),
            id = UUID.randomUUID().toString(),
        )
        persist(crashFile, rotatedCrashFile, entry)
        _crashEntries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    private fun persist(file: File, rotated: File, entry: LogEntry) {
        runCatching {
            val line = json.encodeToString(entry) + "\n"
            synchronized(fileLock) {
                if (file.length() + line.toByteArray().size > MAX_BYTES) {
                    rotated.delete()
                    if (!file.renameTo(rotated)) {
                        file.writeText("")
                    }
                }
                file.appendText(line)
            }
        }
    }

    private fun readEntries(vararg files: File): List<LogEntry> =
        files.flatMap { file ->
            runCatching {
                if (!file.isFile) {
                    emptyList()
                } else {
                    val bounded = readTail(file, MAX_LEGACY_READ_BYTES)
                    val text = bounded.text
                    val decoded = text.lineSequence().mapIndexedNotNull { index, line ->
                        runCatching { json.decodeFromString<LogEntry>(line) }
                            .getOrNull()
                            ?.let { entry ->
                                entry.copy(id = entry.id.ifBlank { "legacy-${file.name}-$index" })
                            }
                    }.toList()
                    if (decoded.isNotEmpty() || (text.isBlank() && !bounded.truncated)) {
                        decoded
                    } else {
                        listOf(
                            LogEntry(
                                ts = file.lastModified(),
                                level = if (file.name.startsWith("crash")) {
                                    LogLevel.Error
                                } else {
                                    LogLevel.Info
                                },
                                tag = "Legacy ${file.name}",
                                message = legacyMessage(bounded),
                                id = "legacy-${file.name}",
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }.takeLast(MAX_ENTRIES)

    private fun legacyMessage(bounded: BoundedTail): String {
        val marker = if (bounded.truncated || bounded.text.length > MAX_LEGACY_MESSAGE_CHARS) {
            "[TRUNCATED: only the last $MAX_LEGACY_READ_BYTES bytes were retained]"
        } else {
            ""
        }
        val available = (MAX_LEGACY_MESSAGE_CHARS - marker.length - 1).coerceAtLeast(0)
        val tail = bounded.text.takeLast(available)
        val combined = if (marker.isBlank()) tail else "$marker\n$tail"
        return SupportRedactor.redact(combined, maxChars = MAX_LEGACY_MESSAGE_CHARS)
    }

    private fun readTail(file: File, maxBytes: Int): BoundedTail {
        val truncated = file.length() > maxBytes
        val skip = (file.length() - maxBytes).coerceAtLeast(0L)
        val bytes = file.inputStream().buffered().use { input ->
            var remaining = skip
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped > 0L) {
                    remaining -= skipped
                } else if (input.read() < 0) {
                    break
                } else {
                    remaining -= 1L
                }
            }
            val output = ByteArrayOutputStream(maxBytes)
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        }
        return BoundedTail(
            text = String(bytes, StandardCharsets.UTF_8),
            truncated = truncated,
        )
    }

    private data class BoundedTail(
        val text: String,
        val truncated: Boolean,
    )

    companion object {
        internal const val MAX_LEGACY_READ_BYTES = 128 * 1024
        internal const val MAX_LEGACY_MESSAGE_CHARS = 32 * 1024
        private const val MAX_ENTRIES = 500
        private const val MAX_BYTES = 256L * 1024L
    }
}
