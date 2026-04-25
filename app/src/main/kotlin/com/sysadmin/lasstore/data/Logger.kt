package com.sysadmin.lasstore.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

enum class LogLevel { Info, Warn, Error }

data class LogEntry(
    val ts: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
)

class Logger(private val context: Context) {
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    private val crashFile: File by lazy {
        File(context.filesDir, "logs").apply { mkdirs() }
            .resolve("crash.log")
    }

    fun info(tag: String, message: String) = append(LogLevel.Info, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.Warn, tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null) {
        val full = throwable?.let { message + "\n" + it.stackTraceToString() } ?: message
        append(LogLevel.Error, tag, full)
        runCatching {
            crashFile.appendText(
                "[${formatTs(System.currentTimeMillis())}] [$tag] $full\n\n"
            )
        }
    }

    private fun append(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, tag, message)
        _entries.update { (it + entry).takeLast(MAX_ENTRIES) }
    }

    fun installCrashHandler() {
        val previous = AtomicReference(Thread.getDefaultUncaughtExceptionHandler())
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            runCatching {
                crashFile.appendText(
                    "[${formatTs(System.currentTimeMillis())}] [UNCAUGHT thread=${thread.name}] $sw\n\n"
                )
            }
            previous.get()?.uncaughtException(thread, throwable)
        }
    }

    private fun formatTs(ts: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    companion object { private const val MAX_ENTRIES = 500 }
}
