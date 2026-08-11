package com.sysadmin.lasstore.data

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class SourceDirectoryFeed(
    val formatVersion: Int = SOURCE_DIRECTORY_FORMAT_VERSION,
    val sources: List<SourceDirectoryEntry> = emptyList(),
)

/** One curated source definition. Exactly one of [github] or [fdroid] must be present. */
@Serializable
data class SourceDirectoryEntry(
    val id: String,
    val name: String,
    val description: String? = null,
    val github: GitHubSource? = null,
    val fdroid: FdroidSource? = null,
) {
    val sourceKey: String
        get() = github?.key ?: fdroid?.key ?: id.trim().lowercase(Locale.US)
}

fun validatedSourceDirectoryUrl(raw: String): HttpUrl? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    if (
        !url.scheme.equals("https", ignoreCase = true) ||
        url.username.isNotBlank() ||
        url.password.isNotBlank() ||
        (url.port != -1 && url.port != 443) ||
        url.queryParameterNames.any { it.lowercase(Locale.US) in SOURCE_DIRECTORY_CREDENTIAL_PARAMS }
    ) {
        return null
    }
    return url
}

fun validateSourceDirectoryUrl(raw: String): String? = when {
    raw.trim().isBlank() -> null
    validatedSourceDirectoryUrl(raw) != null -> null
    else -> "Use an HTTPS source directory URL without credentials."
}

object SourceDirectoryCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun decode(raw: String): SourceDirectoryFeed =
        json.decodeFromString<SourceDirectoryFeed>(raw).also(::validate)

    private fun validate(feed: SourceDirectoryFeed) {
        require(feed.formatVersion == SOURCE_DIRECTORY_FORMAT_VERSION) {
            "Unsupported source directory format version ${feed.formatVersion}"
        }
        require(feed.sources.size <= MAX_SOURCE_DIRECTORY_ENTRIES) {
            "Source directory contains too many source definitions"
        }
        val ids = feed.sources.map { it.id.trim().lowercase(Locale.US) }
        require(ids.none(String::isBlank) && ids.toSet().size == ids.size) {
            "Source directory source ids must be unique and non-blank"
        }
        val sourceKeys = feed.sources.map(SourceDirectoryEntry::sourceKey)
        require(sourceKeys.toSet().size == sourceKeys.size) {
            "Source directory source identities must be unique"
        }
        feed.sources.forEach { entry ->
            require(entry.id.length <= MAX_SOURCE_DIRECTORY_FIELD_CHARS) {
                "Source directory id is too long"
            }
            require(entry.name.isNotBlank() && entry.name.length <= MAX_SOURCE_DIRECTORY_FIELD_CHARS) {
                "Source directory name is invalid"
            }
            require(entry.description.orEmpty().length <= MAX_SOURCE_DIRECTORY_DESCRIPTION_CHARS) {
                "Source directory description is too long"
            }
            require((entry.github != null) xor (entry.fdroid != null)) {
                "Each source directory entry must describe one source type"
            }
            entry.github?.let { source ->
                validateSources(listOf(source))?.let { error -> throw IllegalArgumentException(error) }
            }
            entry.fdroid?.let { source ->
                validateFdroidSources(listOf(source))?.let { error ->
                    throw IllegalArgumentException(error)
                }
            }
        }
    }
}

class SourceDirectoryClient(
    private val client: OkHttpClient = defaultClient(),
    private val maxFeedBytes: Long = MAX_SOURCE_DIRECTORY_BYTES,
) {
    suspend fun fetch(url: String): SourceDirectoryFeed = withContext(Dispatchers.IO) {
        val safeUrl = validatedSourceDirectoryUrl(url)
            ?: throw IOException("Source directory URL must use HTTPS without credentials")
        val request = Request.Builder()
            .url(safeUrl)
            .header("Accept", "application/json")
            .header("User-Agent", "LocalAndroidStore")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Source directory request returned HTTP ${response.code}")
            }
            val body = response.body
            if (body.contentLength() > maxFeedBytes) {
                throw IOException("Source directory exceeds the size limit")
            }
            val bytes = body.bytes()
            if (bytes.size > maxFeedBytes) {
                throw IOException("Source directory exceeds the size limit")
            }
            SourceDirectoryCodec.decode(String(bytes, StandardCharsets.UTF_8))
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}

const val SOURCE_DIRECTORY_FORMAT_VERSION = 1
const val MAX_SOURCE_DIRECTORY_ENTRIES = 128
const val MAX_SOURCE_DIRECTORY_BYTES = 1024L * 1024L
private const val MAX_SOURCE_DIRECTORY_FIELD_CHARS = 160
private const val MAX_SOURCE_DIRECTORY_DESCRIPTION_CHARS = 640
private val SOURCE_DIRECTORY_CREDENTIAL_PARAMS = setOf(
    "token",
    "pat",
    "access_token",
    "client_secret",
    "password",
    "api_key",
)
