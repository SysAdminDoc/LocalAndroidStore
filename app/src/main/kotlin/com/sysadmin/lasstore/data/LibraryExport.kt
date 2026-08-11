package com.sysadmin.lasstore.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class LibraryExportSourceDocument(
    val formatVersion: Int = LIBRARY_EXPORT_FORMAT_VERSION,
    val github: List<GitHubSource> = emptyList(),
    val fdroid: List<FdroidSource> = emptyList(),
)

@Serializable
data class LibraryVersionsDocument(
    val formatVersion: Int = LIBRARY_EXPORT_FORMAT_VERSION,
    val generatedAtEpochMillis: Long = 0L,
    val collections: List<LibraryCollectionSnapshot> = emptyList(),
    val entries: List<LibraryEntrySnapshot> = emptyList(),
    val installs: List<LibraryRestoreEntry> = emptyList(),
)

/** A verified install identity that can be looked up again in a configured source. */
@Serializable
data class LibraryRestoreEntry(
    val applicationId: String,
    val versionCode: Long,
    val versionName: String? = null,
    val apkSha256: String = "",
    val certSha256: String = "",
    val manifestSha256: String = "",
    val sourceKey: String = "",
    val owner: String = "",
    val repo: String = "",
    val tagName: String = "",
    val assetName: String = "",
    val sourceUrl: String = "",
) {
    val key: String
        get() = listOf(
            sourceKey,
            owner,
            repo,
            applicationId,
            versionCode.toString(),
            tagName,
            assetName,
        ).joinToString("/").lowercase(Locale.US)

    companion object {
        fun from(entry: ApkLockfileEntry): LibraryRestoreEntry = LibraryRestoreEntry(
            applicationId = entry.applicationId,
            versionCode = entry.versionCode,
            versionName = entry.versionName,
            apkSha256 = entry.apkSha256,
            certSha256 = entry.certSha256,
            manifestSha256 = entry.manifestSha256,
            sourceKey = entry.sourceKey,
            owner = entry.owner,
            repo = entry.repo,
            tagName = entry.tagName,
            assetName = entry.assetName,
            sourceUrl = entry.sourceUrl,
        )
    }
}

data class ParsedLibraryExport(
    val sources: LibraryExportSourceDocument,
    val library: LibrarySnapshot,
    val installs: List<LibraryRestoreEntry>,
)

/** Pure format codec shared by the Android import/export flow and unit tests. */
object LibraryExportCodec {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeSources(document: LibraryExportSourceDocument): String =
        json.encodeToString(document)

    fun encodeVersions(document: LibraryVersionsDocument): String =
        json.encodeToString(document)

    fun decodeSources(raw: String): LibraryExportSourceDocument =
        json.decodeFromString<LibraryExportSourceDocument>(raw).also(::validateSources)

    fun decodeVersions(raw: String): LibraryVersionsDocument =
        json.decodeFromString<LibraryVersionsDocument>(raw).also(::validateVersions)

    fun toParsedExport(
        sources: LibraryExportSourceDocument,
        versions: LibraryVersionsDocument,
    ): ParsedLibraryExport {
        validateSources(sources)
        validateVersions(versions)
        return ParsedLibraryExport(
            sources = sources,
            library = LibrarySnapshot(
                collections = versions.collections,
                entries = versions.entries,
            ),
            installs = versions.installs,
        )
    }

    private fun validateSources(document: LibraryExportSourceDocument) {
        require(document.formatVersion == LIBRARY_EXPORT_FORMAT_VERSION) {
            "Unsupported library source format version ${document.formatVersion}"
        }
        require(document.github.size <= MAX_EXPORTED_SOURCES) {
            "The library export contains too many GitHub sources"
        }
        require(document.fdroid.size <= MAX_EXPORTED_SOURCES) {
            "The library export contains too many F-Droid sources"
        }
        validateSources(document.github)?.let { error ->
            throw IllegalArgumentException(error)
        }
        validateFdroidSources(document.fdroid)?.let { error ->
            throw IllegalArgumentException(error)
        }
    }

    private fun validateVersions(document: LibraryVersionsDocument) {
        require(document.formatVersion == LIBRARY_EXPORT_FORMAT_VERSION) {
            "Unsupported library lockfile format version ${document.formatVersion}"
        }
        require(document.collections.size <= MAX_EXPORTED_COLLECTIONS) {
            "The library export contains too many collections"
        }
        require(document.entries.size <= MAX_EXPORTED_ENTRIES) {
            "The library export contains too many library entries"
        }
        require(document.installs.size <= MAX_EXPORTED_INSTALLS) {
            "The library export contains too many installed entries"
        }
        require(document.collections.all { collection ->
            collection.id.length <= MAX_EXPORTED_FIELD_CHARS &&
                collection.name.length <= MAX_EXPORTED_FIELD_CHARS
        }) { "The library export contains an oversized collection" }
        require(document.entries.all { entry ->
            entry.key.length <= MAX_EXPORTED_FIELD_CHARS &&
                entry.collectionIds.size <= MAX_EXPORTED_COLLECTIONS &&
                entry.collectionIds.all { it.length <= MAX_EXPORTED_FIELD_CHARS }
        }) { "The library export contains an oversized library entry" }
        require(document.installs.all(::validRestoreEntry)) {
            "The library export contains an invalid install entry"
        }
    }

    private fun validRestoreEntry(entry: LibraryRestoreEntry): Boolean =
        entry.applicationId.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.applicationId.matches(ANDROID_PACKAGE_NAME) &&
            entry.versionCode >= 0L &&
            entry.sourceKey.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.owner.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.repo.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.tagName.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.assetName.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.sourceUrl.length <= MAX_EXPORTED_URL_CHARS &&
            entry.apkSha256.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.certSha256.length <= MAX_EXPORTED_FIELD_CHARS &&
            entry.manifestSha256.length <= MAX_EXPORTED_FIELD_CHARS &&
            (entry.owner.isNotBlank() || entry.sourceUrl.isNotBlank())

    private val ANDROID_PACKAGE_NAME = Regex(
        "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
    )

    private const val MAX_EXPORTED_FIELD_CHARS = 240
    private const val MAX_EXPORTED_URL_CHARS = 2048
}

class LibraryRestoreStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun pending(): List<LibraryRestoreEntry> = preferences
        .getString(KEY_ENTRIES, null)
        ?.let { raw -> runCatching { json.decodeFromString<List<LibraryRestoreEntry>>(raw) }.getOrNull() }
        .orEmpty()
        .filter(::validEntry)
        .distinctBy(LibraryRestoreEntry::key)

    @Synchronized
    fun replace(entries: Collection<LibraryRestoreEntry>) {
        val clean = entries
            .filter(::validEntry)
            .distinctBy(LibraryRestoreEntry::key)
            .take(MAX_PENDING_RESTORE_ENTRIES)
        check(
            preferences.edit()
                .putString(KEY_ENTRIES, json.encodeToString(clean))
                .commit(),
        ) { "Could not persist library restore plan" }
    }

    @Synchronized
    fun remove(entry: LibraryRestoreEntry) {
        replace(pending().filterNot { it.key == entry.key })
    }

    @Synchronized
    fun removeInstalled(info: com.sysadmin.lasstore.domain.AppInfo, metadata: ApkMetadata) {
        val remaining = pending().filterNot { entry ->
            entry.applicationId.equals(metadata.applicationId, ignoreCase = true) &&
                entry.versionCode == metadata.versionCode &&
                (entry.sourceKey.isBlank() || entry.sourceKey.equals(info.sourceKey, true)) &&
                (entry.owner.isBlank() || entry.owner.equals(info.owner, true)) &&
                (entry.repo.isBlank() || entry.repo.equals(info.repo, true))
        }
        if (remaining.size != pending().size) replace(remaining)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(KEY_ENTRIES).commit()) {
            "Could not clear library restore plan"
        }
    }

    private fun validEntry(entry: LibraryRestoreEntry): Boolean =
        entry.applicationId.matches(ANDROID_PACKAGE_NAME) &&
            entry.versionCode >= 0L &&
            entry.key.length <= MAX_RESTORE_KEY_CHARS

    private companion object {
        const val PREFERENCES_NAME = "las_library_restore_v1"
        const val KEY_ENTRIES = "entries"
        const val MAX_PENDING_RESTORE_ENTRIES = 512
        const val MAX_RESTORE_KEY_CHARS = 1024
        val ANDROID_PACKAGE_NAME = Regex(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
        )
    }
}

class LibraryExportStore(
    private val context: Context,
    private val library: LibraryStore,
    private val apkLockfile: ApkLockfileStore,
) {
    private val outputDirectory = File(context.cacheDir, "library-exports")

    fun create(settings: AppSettings): File {
        outputDirectory.mkdirs()
        outputDirectory.listFiles()
            ?.filter { it.isFile && it.extension == "las-library" }
            ?.sortedByDescending(File::lastModified)
            ?.drop(2)
            ?.forEach(File::delete)

        val output = File(
            outputDirectory,
            "las-library-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.las-library",
        )
        val sources = LibraryExportSourceDocument(
            github = normalizeSources(settings.sources).take(MAX_EXPORTED_SOURCES),
            fdroid = normalizeFdroidSources(settings.fdroidSources).take(MAX_EXPORTED_SOURCES),
        )
        val versions = LibraryVersionsDocument(
            generatedAtEpochMillis = System.currentTimeMillis(),
            collections = library.snapshot().collections,
            entries = library.snapshot().entries,
            installs = apkLockfile.read().entries
                .map(LibraryRestoreEntry::from)
                .map(::sanitizeRestoreEntry)
                .filterNotNull()
                .take(MAX_EXPORTED_INSTALLS),
        )
        LibraryExportCodec.toParsedExport(sources, versions)
        try {
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                zip.addText("sources.json", LibraryExportCodec.encodeSources(sources))
                zip.addText("library.las-versions", LibraryExportCodec.encodeVersions(versions))
            }
        } catch (throwable: Throwable) {
            output.delete()
            throw throwable
        }
        return output
    }

    fun read(uri: Uri): ParsedLibraryExport {
        val entries = mutableMapOf<String, String>()
        var totalBytes = 0
        context.contentResolver.openInputStream(uri)?.use { source ->
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    require(entry.name in ALLOWED_IMPORT_ENTRIES) {
                        "Unsupported library export entry"
                    }
                    require(entries[entry.name] == null) {
                        "The library export contains a duplicate entry"
                    }
                    val content = readBounded(
                        zip,
                        minOf(MAX_IMPORT_ENTRY_BYTES, MAX_IMPORT_BYTES - totalBytes),
                    )
                    totalBytes += content.size
                    entries[entry.name] = content.toString(StandardCharsets.UTF_8)
                    require(totalBytes <= MAX_IMPORT_BYTES) {
                        "The library export is too large"
                    }
                }
            }
        } ?: error("The selected library export could not be opened")
        val sources = entries["sources.json"]?.let(LibraryExportCodec::decodeSources)
            ?: error("The library export is missing sources.json")
        val versions = entries["library.las-versions"]?.let(LibraryExportCodec::decodeVersions)
            ?: error("The library export is missing library.las-versions")
        return LibraryExportCodec.toParsedExport(sources, versions)
    }

    fun shareIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND)
            .setType("application/octet-stream")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "LocalAndroidStore library export")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun ZipOutputStream.addText(name: String, value: String) {
        putNextEntry(ZipEntry(name))
        write(value.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun readBounded(input: InputStream, remainingBytes: Int): ByteArray {
        require(remainingBytes > 0) { "The library export is too large" }
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            if (result.size() > remainingBytes - count) {
                throw IllegalArgumentException("The library export is too large")
            }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }

    private fun sanitizeRestoreEntry(entry: LibraryRestoreEntry): LibraryRestoreEntry? {
        val uri = runCatching { Uri.parse(entry.sourceUrl) }.getOrNull()
        val safeUrl = if (uri?.let { parsed ->
                parsed.scheme.equals("https", ignoreCase = true) &&
                    !parsed.host.isNullOrBlank() &&
                    parsed.userInfo.isNullOrBlank() &&
                    parsed.queryParameterNames.none {
                        it.lowercase(Locale.US) in CREDENTIAL_QUERY_NAMES
                    }
            } == true
        ) {
            entry.sourceUrl
        } else {
            ""
        }
        return entry.copy(sourceUrl = safeUrl).takeIf {
            it.owner.isNotBlank() || it.sourceUrl.isNotBlank()
        }
    }

    private companion object {
        const val MAX_EXPORTED_SOURCES = 64
        const val MAX_EXPORTED_COLLECTIONS = 128
        const val MAX_EXPORTED_ENTRIES = 2048
        const val MAX_EXPORTED_INSTALLS = 512
        const val MAX_IMPORT_ENTRY_BYTES = 2 * 1024 * 1024
        const val MAX_IMPORT_BYTES = 4 * 1024 * 1024
        val ALLOWED_IMPORT_ENTRIES = setOf("sources.json", "library.las-versions")
        val CREDENTIAL_QUERY_NAMES = setOf(
            "token",
            "pat",
            "access_token",
            "client_secret",
            "password",
            "api_key",
            "signature",
            "sig",
        )
    }
}

private const val LIBRARY_EXPORT_FORMAT_VERSION = 1
private const val MAX_EXPORTED_SOURCES = 64
private const val MAX_EXPORTED_COLLECTIONS = 128
private const val MAX_EXPORTED_ENTRIES = 2048
private const val MAX_EXPORTED_INSTALLS = 512
