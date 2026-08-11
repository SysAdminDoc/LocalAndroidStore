package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedGitHubResponse(
    val sourceKey: String,
    val url: String,
    val etag: String,
    val body: String,
    val cachedAtEpochMillis: Long,
    val credentialScope: String = LEGACY_CREDENTIAL_SCOPE,
)

interface GitHubResponseCacheStore {
    fun read(sourceKey: String, url: String, credentialScope: String): CachedGitHubResponse?
    fun write(response: CachedGitHubResponse)
    fun purgeSource(sourceKey: String)
}

class FileGitHubResponseCache(context: Context) : GitHubResponseCacheStore {
    private val directory = File(context.filesDir, "catalog/http").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun read(sourceKey: String, url: String, credentialScope: String): CachedGitHubResponse? {
        val file = fileFor(sourceKey, url, credentialScope)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<CachedGitHubResponse>(file.readText()) }
            .getOrNull()
            ?.takeIf {
                it.sourceKey == sourceKey &&
                    it.url == url &&
                    it.credentialScope == credentialScope
            }
    }

    override fun write(response: CachedGitHubResponse) {
        val file = fileFor(response.sourceKey, response.url, response.credentialScope)
        writeAtomically(file, json.encodeToString(response))
    }

    override fun purgeSource(sourceKey: String) {
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.extension == "json" }
            .forEach { file ->
                val belongsToSource = runCatching {
                    json.decodeFromString<CachedGitHubResponse>(file.readText()).sourceKey == sourceKey
                }.getOrDefault(false)
                if (belongsToSource) file.delete()
            }
    }

    private fun fileFor(sourceKey: String, url: String, credentialScope: String): File =
        File(directory, "${sha256("$sourceKey\n$credentialScope\n$url")}.json")
}

@Serializable
data class CatalogSnapshot(
    val schemaVersion: Int = 1,
    val sourceKey: String,
    val sourceLabel: String,
    val refreshedAtEpochMillis: Long,
    val apps: List<AppInfo>,
)

interface CatalogSnapshotRepository {
    fun read(sourceKey: String): CatalogSnapshot?
    fun write(snapshot: CatalogSnapshot)
    fun purge(sourceKey: String)
}

class CatalogSnapshotStore(context: Context) : CatalogSnapshotRepository {
    private val directory = File(context.filesDir, "catalog/snapshots").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun read(sourceKey: String): CatalogSnapshot? {
        val file = fileFor(sourceKey)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<CatalogSnapshot>(file.readText()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == SCHEMA_VERSION && it.sourceKey == sourceKey }
    }

    override fun write(snapshot: CatalogSnapshot) {
        require(snapshot.schemaVersion == SCHEMA_VERSION)
        writeAtomically(fileFor(snapshot.sourceKey), json.encodeToString(snapshot))
    }

    override fun purge(sourceKey: String) {
        fileFor(sourceKey).delete()
    }

    private fun fileFor(sourceKey: String): File =
        File(directory, "${sha256(sourceKey)}.json")

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

private fun writeAtomically(target: File, content: String) {
    target.parentFile?.mkdirs()
    val lock = WRITE_LOCKS.computeIfAbsent(target.absolutePath) { Any() }
    synchronized(lock) {
        val temporary = File(
            target.parentFile,
            "${target.name}.${UUID.randomUUID()}.tmp",
        )
        try {
            temporary.writeText(content)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }
}

private val WRITE_LOCKS = ConcurrentHashMap<String, Any>()

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

const val ANONYMOUS_CREDENTIAL_SCOPE = "anonymous-v1"
const val AUTHENTICATED_CREDENTIAL_SCOPE = "authenticated-v1"
private const val LEGACY_CREDENTIAL_SCOPE = "legacy-unscoped"
