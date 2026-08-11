package com.sysadmin.lasstore.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.sysadmin.lasstore.domain.ReleaseChannel
import com.sysadmin.lasstore.domain.deriveReleaseChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.SSLHandshakeException
import java.util.Locale
import kotlin.math.min
import kotlin.random.Random
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    val rateLimitRemaining: Long? = null,
    val rateLimitResetEpochMillis: Long? = null,
    val acceptedScopes: Set<String> = emptySet(),
) : IOException(message)

class NetworkUnavailableException :
    IOException("No validated internet connection is available.")

class InvalidReleaseAssetDigestException(
    val suppliedDigest: String,
) : IOException("GitHub published an unsupported release asset digest.")

class ReleaseAssetDigestMismatchException(
    val expectedDigest: String,
    val actualDigest: String,
) : IOException(
    "Downloaded release asset failed GitHub SHA-256 verification " +
        "(expected=$expectedDigest, actual=$actualDigest).",
)

/** Returns lowercase SHA-256 hex, accepting GitHub's `sha256:` form. */
fun normalizeSha256Digest(value: String?): String? {
    val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val hex = if (trimmed.startsWith("sha256:", ignoreCase = true)) {
        trimmed.substring("sha256:".length)
    } else {
        trimmed
    }
    return hex.takeIf {
        it.length == 64 && it.all { character -> character.lowercaseChar() in HEX_DIGITS }
    }?.lowercase(Locale.US)
}

data class GitHubConnectionResult(
    val requestedOwner: String,
    val authenticatedLogin: String?,
    val ownerExists: Boolean,
    val authenticatedOwnerAccess: Boolean,
    val accessibleRepoCount: Int,
    val tokenScopes: Set<String>,
    val acceptedScopes: Set<String>,
    val rateLimitRemaining: Long?,
    val rateLimitResetEpochMillis: Long?,
)

/**
 * Repository discovery with an explicit completeness contract.
 *
 * [omittedCount] is an exact count when [omittedCountIsLowerBound] is false and a known lower
 * bound when GitHub returned a full overflow page. [continuationPage] is the next page a future
 * continuation could request; the current catalog deliberately stops at the bounded policy.
 */
data class GitHubRepoListResult(
    val repos: List<GhRepo>,
    val fetchedCount: Int = repos.size,
    val omittedCount: Int = 0,
    val omittedCountIsLowerBound: Boolean = false,
    val continuationPage: Int? = null,
) {
    val isTruncated: Boolean get() = continuationPage != null
}

data class GitHubReleaseHistoryPage(
    val releases: List<GhRelease>,
    val page: Int,
    val hasMore: Boolean,
)

interface GitHubGateway {
    suspend fun listUserRepos(
        user: String,
        patOverride: String? = null,
        sourceKey: String = user,
    ): List<GhRepo>

    suspend fun listUserReposResult(
        user: String,
        patOverride: String? = null,
        sourceKey: String = user,
    ): GitHubRepoListResult = GitHubRepoListResult(
        repos = listUserRepos(
            user = user,
            patOverride = patOverride,
            sourceKey = sourceKey,
        ),
    )

    suspend fun latestRelease(
        owner: String,
        repo: String,
        includePrereleases: Boolean,
        patOverride: String? = null,
        sourceKey: String = owner,
    ): GhRelease?

    /**
     * Returns the newest release in [channel], falling back to the newest published release when
     * the bounded history has no matching channel. The default keeps test and alternate gateways
     * compatible while production GitHub discovery can honor a stored channel preference.
     */
    suspend fun latestReleaseForChannel(
        owner: String,
        repo: String,
        channel: ReleaseChannel,
        patOverride: String? = null,
        sourceKey: String = owner,
    ): GhRelease? = latestRelease(
        owner = owner,
        repo = repo,
        includePrereleases = true,
        patOverride = patOverride,
        sourceKey = sourceKey,
    )

    suspend fun listReleaseHistory(
        owner: String,
        repo: String,
        includePrereleases: Boolean,
        page: Int = 1,
        perPage: Int = 20,
        patOverride: String? = null,
        sourceKey: String = owner,
    ): GitHubReleaseHistoryPage = GitHubReleaseHistoryPage(
        releases = emptyList(),
        page = page,
        hasMore = false,
    )
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
    val digest: String? = null,
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
    private val maxDownloadBytes: Long = MAX_DOWNLOAD_BYTES,
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
    ): List<GhRepo> = listUserReposResult(user, patOverride, sourceKey).repos

    override suspend fun listUserReposResult(
        user: String,
        patOverride: String?,
        sourceKey: String,
    ): GitHubRepoListResult = withContext(Dispatchers.IO) {
        val source = user.trim()
        val sourcePath = encodePathSegment(source)
        val publicResult = listReposPaged(
            urlForPage = { page ->
                "$apiBaseUrl/users/$sourcePath/repos?per_page=100&type=owner&sort=updated&page=$page"
            },
            patOverride = patOverride,
            sourceKey = sourceKey,
        )
        val authenticatedResult = if (hasAuth(patOverride)) {
            listReposPaged(
                urlForPage = { page ->
                    "$apiBaseUrl/user/repos?" +
                        "per_page=100&visibility=all&affiliation=owner,organization_member&sort=updated&page=$page"
                },
                patOverride = patOverride,
                sourceKey = sourceKey,
            ).let { result ->
                result.copy(repos = result.repos.filter {
                    it.owner.login.equals(source, ignoreCase = true)
                })
            }
        } else {
            null
        }
        val pageResults = listOfNotNull(publicResult, authenticatedResult)
        val truncated = pageResults.firstOrNull { it.isTruncated }
        GitHubRepoListResult(
            repos = pageResults
                .flatMap { it.repos }
                .distinctBy { it.fullName.lowercase(Locale.US) },
            fetchedCount = pageResults.sumOf { it.fetchedCount },
            omittedCount = pageResults.sumOf { it.omittedCount },
            omittedCountIsLowerBound = pageResults.any { it.omittedCountIsLowerBound },
            continuationPage = truncated?.continuationPage,
        )
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

    override suspend fun latestReleaseForChannel(
        owner: String,
        repo: String,
        channel: ReleaseChannel,
        patOverride: String?,
        sourceKey: String,
    ): GhRelease? = withContext(Dispatchers.IO) {
        val body = getJson(
            "$apiBaseUrl/repos/${encodePathSegment(owner)}/${encodePathSegment(repo)}" +
                "/releases?per_page=10",
            patOverride,
            sourceKey,
        ) ?: return@withContext null
        val published = json.decodeFromString<List<GhRelease>>(body).filterNot { it.draft }
        published.firstOrNull {
            deriveReleaseChannel(it.tagName, it.prerelease) == channel
        } ?: published.firstOrNull()
    }

    override suspend fun listReleaseHistory(
        owner: String,
        repo: String,
        includePrereleases: Boolean,
        page: Int,
        perPage: Int,
        patOverride: String?,
        sourceKey: String,
    ): GitHubReleaseHistoryPage = withContext(Dispatchers.IO) {
        require(page >= 1) { "Release history pages start at one" }
        require(perPage in 1..RELEASE_HISTORY_PAGE_SIZE) {
            "Release history page size must be between 1 and $RELEASE_HISTORY_PAGE_SIZE"
        }
        val boundedPage = page.coerceAtMost(MAX_RELEASE_HISTORY_PAGES)
        val body = getJson(
            "$apiBaseUrl/repos/${encodePathSegment(owner)}/${encodePathSegment(repo)}/releases" +
                "?per_page=$perPage&page=$boundedPage",
            patOverride,
            sourceKey,
        ) ?: "[]"
        val raw = json.decodeFromString<List<GhRelease>>(body)
        val releases = raw.filter { release ->
            !release.draft && (includePrereleases || !release.prerelease)
        }
        GitHubReleaseHistoryPage(
            releases = releases,
            page = boundedPage,
            hasMore = raw.size == perPage && boundedPage < MAX_RELEASE_HISTORY_PAGES,
        )
    }

    suspend fun testConnection(
        user: String,
        patOverride: String? = null,
    ): GitHubConnectionResult = withContext(Dispatchers.IO) {
        ensureNetworkAvailable()
        val owner = user.trim()
        if (owner.isBlank()) throw IOException("A GitHub user or organization is required")
        val ownerPath = encodePathSegment(owner)
        val ownerResponse = executeConnectionRequest(
            url = "$apiBaseUrl/users/$ownerPath",
            patOverride = patOverride,
        )
        if (!hasAuth(patOverride)) {
            return@withContext GitHubConnectionResult(
                requestedOwner = owner,
                authenticatedLogin = null,
                ownerExists = true,
                authenticatedOwnerAccess = false,
                accessibleRepoCount = 0,
                tokenScopes = emptySet(),
                acceptedScopes = ownerResponse.acceptedScopes,
                rateLimitRemaining = ownerResponse.rateLimitRemaining,
                rateLimitResetEpochMillis = ownerResponse.rateLimitResetEpochMillis,
            )
        }

        val identity = executeConnectionRequest(
            url = "$apiBaseUrl/user",
            patOverride = patOverride,
        )
        val authenticatedLogin = json.decodeFromString<GhAuthenticatedUser>(identity.body).login
        val repositories = executeConnectionRequest(
            url = "$apiBaseUrl/user/repos?per_page=100&visibility=all&" +
                "affiliation=owner,organization_member&sort=updated&page=1",
            patOverride = patOverride,
        )
        val accessibleRepos = json.decodeFromString<List<GhRepo>>(repositories.body)
            .count { it.owner.login.equals(owner, ignoreCase = true) }
        GitHubConnectionResult(
            requestedOwner = owner,
            authenticatedLogin = authenticatedLogin,
            ownerExists = true,
            authenticatedOwnerAccess = authenticatedLogin.equals(owner, ignoreCase = true) ||
                accessibleRepos > 0,
            accessibleRepoCount = accessibleRepos,
            tokenScopes = identity.oauthScopes,
            acceptedScopes = repositories.acceptedScopes.ifEmpty { identity.acceptedScopes },
            rateLimitRemaining = repositories.rateLimitRemaining
                ?: identity.rateLimitRemaining
                ?: ownerResponse.rateLimitRemaining,
            rateLimitResetEpochMillis = repositories.rateLimitResetEpochMillis
                ?: identity.rateLimitResetEpochMillis
                ?: ownerResponse.rateLimitResetEpochMillis,
        )
    }

    fun purgeSourceCache(sourceKey: String) {
        responseCache?.purgeSource(sourceKey)
    }

    suspend fun download(
        url: String,
        target: File,
        patOverride: String? = null,
        expectedDigest: String? = null,
        partialFile: File? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File {
        ensureNetworkAvailable()
        val initialUrl = validateDownloadUrl(url)
        val expectedSha256 = expectedDigest
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { supplied ->
                normalizeSha256Digest(supplied)
                    ?: throw InvalidReleaseAssetDigestException(supplied)
            }
        target.parentFile?.mkdirs()
        val partial = partialFile ?: File("${target.absolutePath}.part")
        partial.parentFile?.mkdirs()
        val resumable = partialFile != null
        if (!resumable) partial.delete()
        target.delete()
        val resumeOffset = if (resumable && partial.isFile) partial.length() else 0L
        if (resumeOffset > maxDownloadBytes) {
            partial.delete()
            throw IOException(
                "Partial release asset exceeds the ${maxDownloadBytes / (1024 * 1024)} MiB limit",
            )
        }
        val request = Request.Builder().url(initialUrl).apply {
            authHeaders(patOverride).forEach { (key, value) -> header(key, value) }
            if (resumeOffset > 0L) header("Range", "bytes=$resumeOffset-")
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val activeCall = java.util.concurrent.atomic.AtomicReference<Call>()
            val downloadClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            continuation.invokeOnCancellation {
                activeCall.get()?.cancel()
                target.delete()
                if (!resumable) partial.delete()
            }
            fun fail(throwable: Throwable, preservePartial: Boolean) {
                if (!preservePartial) partial.delete()
                target.delete()
                if (continuation.isActive) continuation.resumeWithException(throwable)
            }

            fun enqueue(nextRequest: Request, redirects: Int) {
                if (!continuation.isActive) return
                val call = downloadClient.newCall(nextRequest)
                activeCall.set(call)
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        fail(
                            e,
                            preservePartial = resumable && partial.isFile && partial.length() > 0L,
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        var preservePartial = resumable && partial.isFile && partial.length() > 0L
                        try {
                            if (response.isRedirect) {
                                val location = response.header("Location")
                                val nextUrl = location
                                    ?.let { response.request.url.resolve(it) }
                                    ?: throw IOException("Release asset redirect had no Location")
                                response.close()
                                if (redirects >= MAX_DOWNLOAD_REDIRECTS) {
                                    throw IOException("Release asset followed too many redirects")
                                }
                                val validated = validateDownloadUrl(nextUrl)
                                val range = nextRequest.header("Range")
                                val redirectedRequest = Request.Builder()
                                    .url(validated)
                                    .header("Accept", "application/vnd.github+json")
                                    .header("User-Agent", "LocalAndroidStore")
                                    .apply { if (range != null) header("Range", range) }
                                    .build()
                                enqueue(redirectedRequest, redirects + 1)
                                return
                            }
                            response.use { current ->
                                if (!current.isSuccessful) {
                                    preservePartial = resumable &&
                                        current.code in 500..599 &&
                                        partial.isFile &&
                                        partial.length() > 0L
                                    throw responseFailure(current, "downloading release asset")
                                }
                                if (resumeOffset > 0L && current.code == 416) {
                                    preservePartial = false
                                    throw IOException("Release asset rejected the requested byte range")
                                }
                                val body: ResponseBody = current.body
                                val total = body.contentLength()
                                val append = resumeOffset > 0L && current.code == 206
                                if (resumeOffset > 0L && !append) partial.delete()
                                val startingBytes = if (append) resumeOffset else 0L
                                val cumulativeTotal = if (total >= 0L) startingBytes + total else -1L
                                if (cumulativeTotal > maxDownloadBytes) {
                                    preservePartial = false
                                    throw IOException(
                                        "Release asset exceeds the ${maxDownloadBytes / (1024 * 1024)} MiB limit",
                                    )
                                }
                                val digest = expectedSha256?.let {
                                    MessageDigest.getInstance("SHA-256")
                                }
                                if (append && digest != null) updateDigest(partial, digest)
                                FileOutputStream(partial, append).use { output ->
                                    body.byteStream().use { source ->
                                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                                        var downloaded = startingBytes
                                        var lastReport = startingBytes
                                        while (true) {
                                            val count = source.read(buffer)
                                            if (count == -1) break
                                            val countLong = count.toLong()
                                            if (downloaded > maxDownloadBytes - countLong) {
                                                preservePartial = false
                                                throw IOException(
                                                    "Release asset exceeds the ${maxDownloadBytes / (1024 * 1024)} MiB limit",
                                                )
                                            }
                                            digest?.update(buffer, 0, count)
                                            output.write(buffer, 0, count)
                                            downloaded += countLong
                                            if (downloaded - lastReport > 64 * 1024L) {
                                                onProgress(downloaded, cumulativeTotal)
                                                lastReport = downloaded
                                            }
                                        }
                                        onProgress(downloaded, cumulativeTotal)
                                        val actualSha256 = digest?.digest()?.toHex()
                                        if (expectedSha256 != null && actualSha256 != expectedSha256) {
                                            preservePartial = false
                                            throw ReleaseAssetDigestMismatchException(
                                                expectedDigest = expectedSha256,
                                                actualDigest = actualSha256.orEmpty(),
                                            )
                                        }
                                    }
                                }
                            }
                            if (target.exists() && !target.delete()) {
                                throw IOException("Could not replace ${target.name}")
                            }
                            if (!partial.renameTo(target)) {
                                throw IOException("Could not finalize ${target.name}")
                            }
                            if (continuation.isActive) {
                                continuation.resume(target)
                            } else {
                                target.delete()
                            }
                        } catch (throwable: Throwable) {
                            fail(throwable, preservePartial)
                        }
                    }
                })
            }

            enqueue(request, redirects = 0)
        }
    }

    private fun updateDigest(file: File, digest: MessageDigest) {
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun validateDownloadUrl(url: String): HttpUrl {
        val parsed = url.toHttpUrlOrNull()
            ?: throw IOException("Release asset URL is malformed")
        return validateDownloadUrl(parsed)
    }

    private fun validateDownloadUrl(parsed: HttpUrl): HttpUrl {
        val configuredApi = apiBaseUrl.toHttpUrlOrNull()
            ?: throw IOException("Configured GitHub API URL is malformed")
        val allowedHost = parsed.host.equals(configuredApi.host, ignoreCase = true) ||
            parsed.host.lowercase(Locale.US) in TRUSTED_ASSET_HOSTS
        val configuredInsecureTestUrl = parsed.host.equals(configuredApi.host, ignoreCase = true) &&
            configuredApi.scheme != "https" && parsed.scheme == configuredApi.scheme
        if (!allowedHost || (parsed.scheme != "https" && !configuredInsecureTestUrl)) {
            throw IOException("Release asset URL is outside the trusted GitHub hosts")
        }
        return parsed
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
        val credentialScope = if (hasAuth(patOverride)) {
            AUTHENTICATED_CREDENTIAL_SCOPE
        } else {
            ANONYMOUS_CREDENTIAL_SCOPE
        }
        val cached = responseCache?.read(sourceKey, url, credentialScope)
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
            val body = resp.body.string()
            resp.header("ETag")?.takeIf { it.isNotBlank() }?.let { etag ->
                responseCache?.write(
                    CachedGitHubResponse(
                        sourceKey = sourceKey,
                        url = url,
                        etag = etag,
                        body = body,
                        cachedAtEpochMillis = System.currentTimeMillis(),
                        credentialScope = credentialScope,
                    )
                )
            }
            return body
        }
    }

    private fun executeConnectionRequest(
        url: String,
        patOverride: String?,
    ): GitHubConnectionResponse {
        val request = Request.Builder().url(url).apply {
            authHeaders(patOverride).forEach { (key, value) -> header(key, value) }
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw responseFailure(response, "testing GitHub connection")
            }
            return GitHubConnectionResponse(
                body = response.body.string(),
                oauthScopes = parseScopes(response.header("X-OAuth-Scopes")),
                acceptedScopes = parseScopes(response.header("X-Accepted-OAuth-Scopes")),
                rateLimitRemaining = response.header("X-RateLimit-Remaining")?.toLongOrNull(),
                rateLimitResetEpochMillis = response.header("X-RateLimit-Reset")
                    ?.toLongOrNull()
                    ?.times(1_000L),
            )
        }
    }

    private suspend fun listReposPaged(
        urlForPage: (Int) -> String,
        patOverride: String?,
        sourceKey: String,
    ): GitHubRepoListResult {
        val out = mutableListOf<GhRepo>()
        var page = 1
        while (true) {
            val body = getJson(urlForPage(page), patOverride, sourceKey) ?: "[]"
            val batch = json.decodeFromString<List<GhRepo>>(body)
            if (batch.isEmpty()) break
            out += batch
            if (batch.size < REPOSITORIES_PER_PAGE) break
            if (page >= MAX_REPO_PAGES) {
                val overflowBody = getJson(urlForPage(page + 1), patOverride, sourceKey) ?: "[]"
                val overflow = json.decodeFromString<List<GhRepo>>(overflowBody)
                if (overflow.isEmpty()) break
                return GitHubRepoListResult(
                    repos = out,
                    fetchedCount = out.size,
                    omittedCount = overflow.size,
                    omittedCountIsLowerBound = overflow.size == REPOSITORIES_PER_PAGE,
                    continuationPage = page + 1,
                )
            }
            page += 1
        }
        return GitHubRepoListResult(repos = out, fetchedCount = out.size)
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
            rateLimitRemaining = remaining,
            rateLimitResetEpochMillis = resetAt,
            acceptedScopes = parseScopes(response.header("X-Accepted-OAuth-Scopes")),
        )
    }

    private fun parseScopes(value: String?): Set<String> = value
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    private fun ensureNetworkAvailable() {
        if (!networkAvailable()) throw NetworkUnavailableException()
    }

    companion object {
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val MAX_DOWNLOAD_BYTES = 200L * 1024L * 1024L
        private const val MAX_DOWNLOAD_REDIRECTS = 5
        private const val MAX_ATTEMPTS = 3
        private const val BASE_RETRY_DELAY_MILLIS = 200L
        private const val MAX_RETRY_DELAY_MILLIS = 2_000L
        private const val MAX_INLINE_RATE_LIMIT_WAIT_MILLIS = 2_000L
        private const val REPOSITORIES_PER_PAGE = 100
        private const val MAX_REPO_PAGES = 50
        private const val RELEASE_HISTORY_PAGE_SIZE = 20
        private const val MAX_RELEASE_HISTORY_PAGES = 10
        private val TRUSTED_ASSET_HOSTS = setOf(
            "api.github.com",
            "github.com",
            "objects.githubusercontent.com",
            "release-assets.githubusercontent.com",
            "github-releases.githubusercontent.com",
        )

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

@Serializable
private data class GhAuthenticatedUser(val login: String)

private data class GitHubConnectionResponse(
    val body: String,
    val oauthScopes: Set<String>,
    val acceptedScopes: Set<String>,
    val rateLimitRemaining: Long?,
    val rateLimitResetEpochMillis: Long?,
)

private const val HEX_DIGITS = "0123456789abcdef"

private fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
