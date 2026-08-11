package com.sysadmin.lasstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Serializable
data class SourceNewsItem(
    val title: String = "",
    val caption: String? = null,
    val date: String? = null,
    val url: String? = null,
    @SerialName("imageURL") val imageUrl: String? = null,
)

/** AltStore-compatible source metadata; all fields are optional for a graceful partial feed. */
@Serializable
data class SourceBranding(
    @SerialName("iconURL") val iconUrl: String? = null,
    @SerialName("headerURL") val headerUrl: String? = null,
    val tintColor: String? = null,
    val featuredApps: List<String> = emptyList(),
    val news: List<SourceNewsItem> = emptyList(),
)

fun validatedSourceBrandingUrl(raw: String): HttpUrl? {
    val url = raw.trim().toHttpUrlOrNull() ?: return null
    return url.takeIf {
        it.scheme == "https" &&
            it.username.isBlank() &&
            it.password.isBlank() &&
            it.port == 443
    }
}

fun validateSourceBrandingUrl(raw: String): String? {
    if (raw.trim().isBlank()) return null
    return validatedSourceBrandingUrl(raw)?.let { null }
        ?: "Use an HTTPS branding feed URL without credentials."
}

class SourceBrandingClient(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    },
) {
    private val imageCache = ConcurrentHashMap<String, ByteArray>()

    suspend fun fetch(url: String): SourceBranding? = withContext(Dispatchers.IO) {
        val safeUrl = validatedSourceBrandingUrl(url) ?: return@withContext null
        val request = Request.Builder()
            .url(safeUrl)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body
            if (body.contentLength() > MAX_BRANDING_BYTES) return@withContext null
            val bytes = body.bytes().takeIf { it.size <= MAX_BRANDING_BYTES }
                ?: return@withContext null
            runCatching {
                json.decodeFromString<SourceBranding>(String(bytes, StandardCharsets.UTF_8))
            }.getOrNull()
        }
    }

    suspend fun fetchImage(url: String): ByteArray? = withContext(Dispatchers.IO) {
        val safeUrl = validatedSourceBrandingUrl(url) ?: return@withContext null
        imageCache[safeUrl.toString()]?.let { return@withContext it }
        val request = Request.Builder()
            .url(safeUrl)
            .header("Accept", "image/*")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body
            if (body.contentLength() > MAX_IMAGE_BYTES) return@withContext null
            val bytes = body.bytes().takeIf { it.size <= MAX_IMAGE_BYTES } ?: return@withContext null
            if (imageCache.size >= MAX_CACHED_IMAGES) imageCache.clear()
            imageCache[safeUrl.toString()] = bytes
            bytes
        }
    }

    private companion object {
        const val MAX_BRANDING_BYTES = 512 * 1024L
        const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
        const val MAX_CACHED_IMAGES = 24

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
