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
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicReference

enum class LogLevel { Info, Warn, Error }

@Serializable
data class LogEntry(
    val ts: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

class Logger(private val context: Context) {
    private val logDir = File(context.filesDir, "logs").apply { mkdirs() }
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
                    val text = file.readText()
                    val decoded = text.lineSequence().mapNotNull { line ->
                        runCatching { json.decodeFromString<LogEntry>(line) }.getOrNull()
                    }.toList()
                    if (decoded.isNotEmpty() || text.isBlank()) {
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
                                message = SupportRedactor.redact(text),
                            ),
                        )
                    }
                }
            }.getOrDefault(emptyList())
        }.takeLast(MAX_ENTRIES)

    companion object {
        private const val MAX_ENTRIES = 500
        private const val MAX_BYTES = 256L * 1024L
    }
}
