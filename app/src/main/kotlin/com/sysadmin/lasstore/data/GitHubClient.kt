package com.sysadmin.lasstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
data class GhRepo(
    val name: String,
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val archived: Boolean = false,
    val fork: Boolean = false,
    val private: Boolean = false,
    @SerialName("stargazers_count") val stars: Int = 0,
    val topics: List<String> = emptyList(),
    @SerialName("default_branch") val defaultBranch: String = "main",
    val owner: GhOwner,
)

@Serializable
data class GhOwner(val login: String)

@Serializable
data class GhRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GhAsset> = emptyList(),
    @SerialName("html_url") val htmlUrl: String,
)

@Serializable
data class GhAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
)

class GitHubClient(
    private val patProvider: () -> String,
    private val logger: Logger,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun authHeaders(): Map<String, String> {
        val pat = patProvider().trim()
        val base = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "LocalAndroidStore",
        )
        return if (pat.isNotEmpty()) base + ("Authorization" to "Bearer $pat") else base
    }

    suspend fun listUserRepos(user: String): List<GhRepo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<GhRepo>()
        var page = 1
        while (true) {
            val url = "https://api.github.com/users/${user}/repos?per_page=100&type=owner&sort=updated&page=$page"
            val body = getJson(url)
            val batch = json.decodeFromString<List<GhRepo>>(body)
            if (batch.isEmpty()) break
            out += batch
            if (batch.size < 100) break
            page += 1
            if (page > 10) break // 1000-repo cap, defensive
        }
        out
    }

    suspend fun latestRelease(owner: String, repo: String, includePrereleases: Boolean): GhRelease? =
        withContext(Dispatchers.IO) {
            if (includePrereleases) {
                val list = runCatching {
                    json.decodeFromString<List<GhRelease>>(
                        getJson("https://api.github.com/repos/$owner/$repo/releases?per_page=10")
                    )
                }.getOrElse { return@withContext null }
                list.firstOrNull { !it.draft }
            } else {
                runCatching {
                    json.decodeFromString<GhRelease>(
                        getJson("https://api.github.com/repos/$owner/$repo/releases/latest")
                    )
                }.getOrNull()
            }
        }

    suspend fun download(url: String, target: File, onProgress: (downloaded: Long, total: Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply {
                authHeaders().forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} downloading $url")
                val body: ResponseBody = resp.body ?: throw IOException("Empty body for $url")
                val total = body.contentLength()
                target.parentFile?.mkdirs()
                target.sink().buffer().use { sink: BufferedSink ->
                    body.source().use { source ->
                        val buf = okio.Buffer()
                        var downloaded = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = source.read(buf, 64 * 1024L)
                            if (n == -1L) break
                            sink.write(buf, n)
                            downloaded += n
                            if (downloaded - lastReport > 64 * 1024L) {
                                onProgress(downloaded, total)
                                lastReport = downloaded
                            }
                        }
                        onProgress(downloaded, total)
                    }
                }
            }
            target
        }

    private fun getJson(url: String): String {
        val req = Request.Builder().url(url).apply {
            authHeaders().forEach { (k, v) -> header(k, v) }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return "[]"
            if (!resp.isSuccessful) {
                logger.warn("GitHub", "GET $url -> HTTP ${resp.code}")
                throw IOException("HTTP ${resp.code} on $url")
            }
            return resp.body?.string() ?: "[]"
        }
    }
}
