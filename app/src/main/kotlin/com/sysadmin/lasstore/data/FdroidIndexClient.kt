package com.sysadmin.lasstore.data

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import com.sysadmin.lasstore.domain.VerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.ProxySelector

fun interface FdroidIndexProvider {
    suspend fun fetch(indexUrl: String): String

    suspend fun verifyEntryJar(indexUrl: String, expectedFingerprint: String): VerifyResult? = null
}

class FdroidIndexClient(
    private val proxySelector: ProxySelector? = null,
    private val client: OkHttpClient = defaultFdroidClient(proxySelector),
    private val networkAvailable: () -> Boolean = { true },
    private val maxIndexBytes: Long = MAX_INDEX_BYTES,
) : FdroidIndexProvider {
    override suspend fun fetch(indexUrl: String): String = withContext(Dispatchers.IO) {
        if (!networkAvailable()) throw NetworkUnavailableException()
        val url = indexUrl.toHttpUrlOrNull()
            ?: throw IOException("F-Droid index URL is malformed")
        require(url.scheme.equals("https", ignoreCase = true)) {
            "F-Droid index URL must use HTTPS"
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "LocalAndroidStore")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("F-Droid index request returned HTTP ${response.code}")
            }
            val body = response.body
            if (body.contentLength() > maxIndexBytes) {
                throw IOException("F-Droid index exceeds the ${maxIndexBytes / (1024 * 1024)} MiB limit")
            }
            val content = body.string()
            if (content.toByteArray(Charsets.UTF_8).size > maxIndexBytes) {
                throw IOException("F-Droid index exceeds the ${maxIndexBytes / (1024 * 1024)} MiB limit")
            }
            content
        }
    }

    override suspend fun verifyEntryJar(
        indexUrl: String,
        expectedFingerprint: String,
    ): VerifyResult = withContext(Dispatchers.IO) {
        if (!networkAvailable()) throw NetworkUnavailableException()
        val index = indexUrl.toHttpUrlOrNull()
            ?: throw IOException("F-Droid index URL is malformed")
        require(index.scheme.equals("https", ignoreCase = true)) {
            "F-Droid index URL must use HTTPS"
        }
        val entryUrl = index.newBuilder()
            .removePathSegment(index.pathSize - 1)
            .addPathSegment("entry.jar")
            .build()
        val request = Request.Builder()
            .url(entryUrl)
            .header("Accept", "application/java-archive")
            .header("User-Agent", "LocalAndroidStore")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("F-Droid entry.jar request returned HTTP ${response.code}")
            }
            val body = response.body
            if (body.contentLength() > MAX_ENTRY_JAR_BYTES) {
                throw IOException("F-Droid entry.jar exceeds the size limit")
            }
            val bytes = body.bytes()
            if (bytes.size > MAX_ENTRY_JAR_BYTES) {
                throw IOException("F-Droid entry.jar exceeds the size limit")
            }
            val temporary = File.createTempFile("las-fdroid-entry-", ".jar")
            try {
                temporary.writeBytes(bytes)
                FdroidEntryJarVerifier.verify(temporary, expectedFingerprint)
            } finally {
                temporary.delete()
            }
        }
    }

    private companion object {
        const val MAX_INDEX_BYTES = 32L * 1024L * 1024L
        const val MAX_ENTRY_JAR_BYTES = 8L * 1024L * 1024L

        fun defaultFdroidClient(proxySelector: ProxySelector? = null): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .apply { proxySelector?.let(::proxySelector) }
            .build()
    }
}
