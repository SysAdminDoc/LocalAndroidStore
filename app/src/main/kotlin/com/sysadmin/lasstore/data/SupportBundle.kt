package com.sysadmin.lasstore.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.sysadmin.lasstore.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object SupportRedactor {
    private val authorization = Regex(
        pattern = """(?im)(\bauthorization\s*[:=]\s*)[^\r\n]+""",
    )
    private val bearer = Regex(
        pattern = """(?i)\bbearer\s+[a-z0-9._~+/\-=]{8,}""",
    )
    private val githubToken = Regex(
        pattern = """(?i)\b(?:github_pat_[a-z0-9_]{20,}|gh[pousr]_[a-z0-9_]{20,})\b""",
    )
    private val namedCredential = Regex(
        pattern = """(?i)\b(token|pat|access[_-]?token|client[_-]?secret|password|api[_-]?key|signing[_-]?(?:key|secret))(\s*[=:]\s*)([^&\s,;"']+)""",
    )
    private val queryCredential = Regex(
        pattern = """(?i)([?&](?:token|pat|access_token|client_secret|password|api_key)=)[^&#\s]+""",
    )
    private val urlUserInfo = Regex(
        pattern = """(?i)\b(https?://)[^/\s@]+@""",
    )

    fun redact(value: String, maxChars: Int = MAX_ENTRY_CHARS): String {
        val redacted = value
            .replace(authorization) { "${it.groupValues[1]}[REDACTED]" }
            .replace(bearer, "Bearer [REDACTED]")
            .replace(githubToken, "[REDACTED]")
            .replace(namedCredential) {
                "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]"
            }
            .replace(queryCredential) { "${it.groupValues[1]}[REDACTED]" }
            .replace(urlUserInfo) { "${it.groupValues[1]}[REDACTED]@" }
        return if (redacted.length > maxChars) {
            redacted.take(maxChars) + "\n[TRUNCATED]"
        } else {
            redacted
        }
    }

    private const val MAX_ENTRY_CHARS = 32 * 1024
}

class SupportBundleExporter(
    private val context: Context,
    private val outputDirectory: File = File(context.cacheDir, "support"),
) {
    fun create(): File {
        outputDirectory.mkdirs()
        pruneOldBundles()
        val output = reserveOutputFile()
        try {
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                zip.addText("metadata.txt", metadata())
                zip.addText(
                    "diagnostics.log",
                    boundedSanitizedLogs("diagnostics.log.1", "diagnostics.log"),
                )
                zip.addText(
                    "install-audit.jsonl",
                    boundedSanitizedLogs("install.log.1", "install.log"),
                )
                zip.addText(
                    "crash.log",
                    boundedSanitizedLogs("crash.log.1", "crash.log"),
                )
            }
        } catch (throwable: Throwable) {
            output.delete()
            throw throwable
        }
        return output
    }

    fun shareIntent(bundle: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            bundle,
        )
        return Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "LocalAndroidStore support bundle")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun metadata(): String = buildString {
        appendLine("format=1")
        appendLine("created_utc=${isoTimestamp()}")
        appendLine("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("android_api=${Build.VERSION.SDK_INT}")
        appendLine("android_release=${SupportRedactor.redact(Build.VERSION.RELEASE)}")
        appendLine(
            "device=${
                SupportRedactor.redact("${Build.MANUFACTURER} ${Build.MODEL}".trim())
            }",
        )
        appendLine("abis=${Build.SUPPORTED_ABIS.joinToString(",")}")
    }

    private fun boundedSanitizedLogs(vararg names: String): String {
        val logDir = File(context.filesDir, "logs")
        val combined = buildString {
            names.forEach { name ->
                val file = File(logDir, name)
                if (file.isFile) {
                    appendLine("---- $name ----")
                    appendLine(readTail(file, MAX_SOURCE_BYTES))
                }
            }
        }
        return SupportRedactor.redact(combined, MAX_ARCHIVE_ENTRY_CHARS)
    }

    private fun readTail(file: File, maxBytes: Int): String {
        val bytes = file.inputStream().buffered().use { input ->
            val skip = (file.length() - maxBytes).coerceAtLeast(0L)
            var remaining = skip
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) break
                remaining -= skipped
            }
            val output = ByteArrayOutputStream(maxBytes)
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (total < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            output.toByteArray()
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun isoTimestamp(): String =
        utcFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(Date())

    private fun fileTimestamp(): String =
        utcFormat("yyyyMMdd-HHmmss").format(Date())

    private fun utcFormat(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    private fun reserveOutputFile(): File {
        repeat(8) {
            val output = File(
                outputDirectory,
                "las-support-${fileTimestamp()}-${UUID.randomUUID().toString().take(8)}.zip",
            )
            if (output.createNewFile()) return output
        }
        throw java.io.IOException("Could not allocate a unique support-bundle filename")
    }

    private fun pruneOldBundles() {
        val now = System.currentTimeMillis()
        val bundles = outputDirectory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("las-support-") && it.extension.equals("zip", true) }
            .orEmpty()
        val recent = bundles.filter { bundle ->
            val age = now - bundle.lastModified()
            bundle.lastModified() <= 0L || age < SUPPORT_BUNDLE_SHARE_SAFETY_MILLIS
        }
        val stale = bundles
            .filterNot { it in recent }
            .sortedByDescending(File::lastModified)
        val staleKeepCount = (MAX_RETAINED_SUPPORT_BUNDLES - 1 - recent.size).coerceAtLeast(0)
        stale.drop(staleKeepCount).forEach { it.delete() }
    }

    private companion object {
        const val MAX_SOURCE_BYTES = 128 * 1024
        const val MAX_ARCHIVE_ENTRY_CHARS = 384 * 1024
    }
}

internal const val MAX_RETAINED_SUPPORT_BUNDLES = 8
internal const val SUPPORT_BUNDLE_SHARE_SAFETY_MILLIS = 24L * 60L * 60L * 1000L
