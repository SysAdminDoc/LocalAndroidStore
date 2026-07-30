package com.sysadmin.lasstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.Response
import okio.BufferedSink
import okio.buffer
import okio.sink
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLHandshakeException
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random
import java.util.concurrent.TimeUnit

enum class GitHubFailureKind {
    Authentication,
    Authorization,
    RateLimited,
    Server,
    Http,
}

class GitHubRequestException(
    val kind: GitHubFailureKind,
    val statusCode: Int,
    val retryAtEpochMillis: Long? = null,
    message: String,
) : IOException(message)

class NetworkUnavailableException :
    IOException("No validated internet connection is available.")

interface GitHubGateway {
    suspend fun listUserRepos(
        user: String,
        patOverride: String? = null,
        sourceKey: String = user,
    ): List<GhRepo>

    suspend fun latestRelease(
        owner: String,
        repo: String,
        includePrereleases: Boolean,
        patOverride: String? = null,
        sourceKey: String = owner,
    ): GhRelease?
}

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
    val id: Long = 0,
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("content_type") val contentType: String = "",
)

class GitHubClient(
    private val patProvider: () -> String,
    private val logger: Logger?,
    private val responseCache: GitHubResponseCacheStore? = null,
    private val client: OkHttpClient = defaultClient(),
    private val apiBaseUrl: String = "https://api.github.com",
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    private val retryJitterMillis: () -> Long = { Random.nextLong(50L, 251L) },
    private val networkAvailable: () -> Boolean = { true },
) : GitHubGateway {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun authHeaders(patOverride: String? = null): Map<String, String> {
        val pat = (patOverride ?: patProvider()).trim()
        val base = mapOf(
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28",
            "User-Agent" to "LocalAndroidStore",
        )
        return if (pat.isNotEmpty()) base + ("Authorization" to "Bearer $pat") else base
    }

    override suspend fun listUserRepos(
        user: String,
        patOverride: String?,
        sourceKey: String,
    ): List<GhRepo> = withContext(Dispatchers.IO) {
        val source = user.trim()
        val sourcePath = encodePathSegment(source)
        val publicRepos = listReposPaged(
            urlForPage = { page ->
                "$apiBaseUrl/users/$sourcePath/repos?per_page=100&type=owner&sort=updated&page=$page"
            },
            patOverride = patOverride,
            sourceKey = sourceKey,
        )
        val authenticatedRepos = if (hasAuth(patOverride)) {
            listReposPaged(
                urlForPage = { page ->
                    "$apiBaseUrl/user/repos?" +
                        "per_page=100&visibility=all&affiliation=owner,organization_member&sort=updated&page=$page"
                },
                patOverride = patOverride,
                sourceKey = sourceKey,
            ).filter { it.owner.login.equals(source, ignoreCase = true) }
        } else {
            emptyList()
        }
        (publicRepos + authenticatedRepos)
            .distinctBy { it.fullName.lowercase(Locale.US) }
    }

    override suspend fun latestRelease(
        owner: String,
        repo: String,
        includePrereleases: Boolean,
        patOverride: String?,
        sourceKey: String,
    ): GhRelease? =
        withContext(Dispatchers.IO) {
            val ownerPath = encodePathSegment(owner)
            val repoPath = encodePathSegment(repo)
            if (includePrereleases) {
                val body = getJson(
                    "$apiBaseUrl/repos/$ownerPath/$repoPath/releases?per_page=10",
                    patOverride,
                    sourceKey,
                ) ?: return@withContext null
                val list = json.decodeFromString<List<GhRelease>>(body)
                list.firstOrNull { !it.draft }
            } else {
                val body = getJson(
                    "$apiBaseUrl/repos/$ownerPath/$repoPath/releases/latest",
                    patOverride,
                    sourceKey,
                ) ?: return@withContext null
                json.decodeFromString<GhRelease>(body)
            }
        }

    suspend fun download(
        url: String,
        target: File,
        patOverride: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            ensureNetworkAvailable()
            val req = Request.Builder().url(url).apply {
                authHeaders(patOverride).forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw responseFailure(resp, "downloading release asset")
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

    private suspend fun getJson(
        url: String,
        patOverride: String? = null,
        sourceKey: String,
    ): String? {
        ensureNetworkAvailable()
        var attempt = 1
        while (true) {
            try {
                return executeJson(url, patOverride, sourceKey)
            } catch (throwable: Throwable) {
                val delayMillis = retryDelayMillis(throwable, attempt) ?: throw throwable
                logger?.warn(
                    "GitHub",
                    "Transient request failure; retrying attempt ${attempt + 1} of $MAX_ATTEMPTS",
                )
                retryDelay(delayMillis)
                attempt += 1
            }
        }
    }

    private fun executeJson(
        url: String,
        patOverride: String?,
        sourceKey: String,
    ): String? {
        val cached = responseCache?.read(sourceKey, url)
        val req = Request.Builder().url(url).apply {
            authHeaders(patOverride).forEach { (k, v) -> header(k, v) }
            cached?.etag?.takeIf { it.isNotBlank() }?.let { header("If-None-Match", it) }
        }.build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 304) {
                return cached?.body
                    ?: throw IOException("GitHub returned 304 without a cached response")
            }
            if (resp.code == 404) return null
            if (!resp.isSuccessful) {
                logger?.warn("GitHub", "GitHub metadata request -> HTTP ${resp.code}")
                throw responseFailure(resp, "requesting GitHub metadata")
            }
            val body = resp.body?.string() ?: "[]"
            resp.header("ETag")?.takeIf { it.isNotBlank() }?.let { etag ->
                responseCache?.write(
                    CachedGitHubResponse(
                        sourceKey = sourceKey,
                        url = url,
                        etag = etag,
                        body = body,
                        cachedAtEpochMillis = System.currentTimeMillis(),
                    )
                )
            }
            return body
        }
    }

    private suspend fun listReposPaged(
        urlForPage: (Int) -> String,
        patOverride: String?,
        sourceKey: String,
    ): List<GhRepo> {
        val out = mutableListOf<GhRepo>()
        var page = 1
        while (true) {
            val body = getJson(urlForPage(page), patOverride, sourceKey) ?: "[]"
            val batch = json.decodeFromString<List<GhRepo>>(body)
            if (batch.isEmpty()) break
            out += batch
            if (batch.size < 100) break
            page += 1
            if (page > 10) break // 1000-repo cap, defensive
        }
        return out
    }

    private fun hasAuth(patOverride: String?): Boolean = (patOverride ?: patProvider()).trim().isNotEmpty()

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun retryDelayMillis(throwable: Throwable, attempt: Int): Long? {
        if (attempt >= MAX_ATTEMPTS ||
            throwable is SSLHandshakeException ||
            throwable is NetworkUnavailableException
        ) {
            return null
        }
        val retryable = when (throwable) {
            is GitHubRequestException ->
                throwable.kind == GitHubFailureKind.Server ||
                    throwable.kind == GitHubFailureKind.RateLimited
            is IOException -> true
            else -> false
        }
        if (!retryable) return null

        if (throwable is GitHubRequestException &&
            throwable.kind == GitHubFailureKind.RateLimited
        ) {
            val wait = throwable.retryAtEpochMillis
                ?.minus(System.currentTimeMillis())
                ?.coerceAtLeast(0L)
                ?: return null
            if (wait > MAX_INLINE_RATE_LIMIT_WAIT_MILLIS) return null
            return wait + retryJitterMillis()
        }

        val exponential = BASE_RETRY_DELAY_MILLIS * (1L shl (attempt - 1))
        return min(MAX_RETRY_DELAY_MILLIS, exponential + retryJitterMillis())
    }

    private fun responseFailure(response: Response, operation: String): GitHubRequestException {
        val remaining = response.header("X-RateLimit-Remaining")?.toLongOrNull()
        val resetAt = response.header("X-RateLimit-Reset")
            ?.toLongOrNull()
            ?.times(1_000L)
        val retryAfter = response.header("Retry-After")
            ?.toLongOrNull()
            ?.let { System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(it) }
        val rateLimited = response.code == 429 ||
            (response.code == 403 && remaining == 0L)
        val kind = when {
            response.code == 401 -> GitHubFailureKind.Authentication
            rateLimited -> GitHubFailureKind.RateLimited
            response.code == 403 -> GitHubFailureKind.Authorization
            response.code in 500..599 -> GitHubFailureKind.Server
            else -> GitHubFailureKind.Http
        }
        val retryAt = if (rateLimited) retryAfter ?: resetAt else null
        val retryCopy = retryAt?.let { " Retry permitted after epoch-millis $it." }.orEmpty()
        return GitHubRequestException(
            kind = kind,
            statusCode = response.code,
            retryAtEpochMillis = retryAt,
            message = "GitHub HTTP ${response.code} while $operation.$retryCopy",
        )
    }

    private fun ensureNetworkAvailable() {
        if (!networkAvailable()) throw NetworkUnavailableException()
    }

    companion object {
        private const val MAX_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MILLIS = 200L
        private const val MAX_RETRY_DELAY_MILLIS = 2_000L
        private const val MAX_INLINE_RATE_LIMIT_WAIT_MILLIS = 2_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
