package com.sysadmin.lasstore.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.sysadmin.lasstore.domain.ReleaseChannel

class GitHubClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.shutdown()
    }

    @Test
    fun etag304ReusesPersistedSourceResponse() = runBlocking {
        val cache = MemoryResponseCache()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("ETag", "\"catalog-v1\"")
                .setBody(REPOSITORY_LIST),
        )
        server.enqueue(MockResponse().setResponseCode(304))
        val client = client(cache = cache)

        val first = client.listUserRepos("alice", sourceKey = "source-a")
        val second = client.listUserRepos("alice", sourceKey = "source-a")

        assertEquals(first, second)
        assertEquals(1, second.size)
        assertEquals(null, server.takeRequest().getHeader("If-None-Match"))
        assertEquals(
            "\"catalog-v1\"",
            server.takeRequest().getHeader("If-None-Match"),
        )
        assertNotNull(
            cache.read(
                "source-a",
                server.url("/users/alice/repos?per_page=100&type=owner&sort=updated&page=1").toString(),
                ANONYMOUS_CREDENTIAL_SCOPE,
            ),
        )
    }

    @Test
    fun authenticatedAndAnonymousResponsesUseSeparateCacheNamespaces() = runBlocking {
        val cache = MemoryResponseCache()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"public\"").setBody(RELEASE))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"private\"").setBody(RELEASE))

        client(cache = cache).latestRelease("alice", "app", false, sourceKey = "source-a")
        client(cache = cache, pat = "secret-token")
            .latestRelease("alice", "app", false, sourceKey = "source-a")

        server.takeRequest()
        assertEquals(null, server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun channelLookupChoosesNewestMatchingRelease() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {"tag_name":"v3-beta1","prerelease":true,"html_url":"https://example.invalid/beta"},
                      {"tag_name":"v2","prerelease":false,"html_url":"https://example.invalid/stable"}
                    ]
                    """.trimIndent(),
                ),
        )

        val release = client().latestReleaseForChannel(
            owner = "alice",
            repo = "app",
            channel = ReleaseChannel.BETA,
            sourceKey = "source-a",
        )

        assertEquals("v3-beta1", release?.tagName)
        assertTrue(server.takeRequest().path.orEmpty().contains("/releases?per_page=10"))
    }

    @Test
    fun purgingSourceCacheRemovesConditionalResponsesBeforeCredentialReuse() = runBlocking {
        val cache = MemoryResponseCache()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"private-v1\"").setBody(RELEASE))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("ETag", "\"private-v2\"").setBody(RELEASE))
        val client = client(cache = cache, pat = "secret-token")

        client.latestRelease("alice", "app", false, sourceKey = "source-a")
        client.purgeSourceCache("source-a")
        client.latestRelease("alice", "app", false, sourceKey = "source-a")

        server.takeRequest()
        assertEquals(null, server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun repositoryListingPaginatesUntilShortPage() = runBlocking {
        val firstPage = (1..100).joinToString(prefix = "[", postfix = "]") { index ->
            repositoryJson("app-$index")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(firstPage))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[${repositoryJson("app-101")}]"),
        )

        val repos = client().listUserRepos("alice", sourceKey = "source-a")

        assertEquals(101, repos.size)
        assertTrue(server.takeRequest().path.orEmpty().endsWith("page=1"))
        assertTrue(server.takeRequest().path.orEmpty().endsWith("page=2"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun repositoryListingContinuesPastTheLegacyThousandRepoBoundary() = runBlocking {
        repeat(10) { page ->
            val start = page * 100 + 1
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        (start until start + 100).joinToString(prefix = "[", postfix = "]") {
                            repositoryJson("app-$it")
                        },
                    ),
            )
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[${repositoryJson("app-1001")}]")
        )

        val result = client().listUserReposResult("alice", sourceKey = "source-a")

        assertEquals(1_001, result.repos.size)
        assertEquals(1_001, result.fetchedCount)
        assertFalse(result.isTruncated)
        assertTrue(server.takeRequest().path.orEmpty().endsWith("page=1"))
        repeat(9) { server.takeRequest() }
        assertTrue(server.takeRequest().path.orEmpty().endsWith("page=11"))
        assertEquals(11, server.requestCount)
    }

    @Test
    fun repositoryListingReportsATypedTruncationAtTheBoundedPolicy() = runBlocking {
        repeat(50) { page ->
            val start = page * 100
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        (start until start + 100).joinToString(prefix = "[", postfix = "]") {
                            repositoryJson("app-$it")
                        },
                    )
            )
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("[${repositoryJson("app-overflow")}]")
        )

        val result = client().listUserReposResult("alice", sourceKey = "source-a")

        assertTrue(result.isTruncated)
        assertEquals(5_000, result.fetchedCount)
        assertEquals(1, result.omittedCount)
        assertFalse(result.omittedCountIsLowerBound)
        assertEquals(51, result.continuationPage)
        assertEquals(5_000, result.repos.size)
        assertEquals(51, server.requestCount)
    }

    @Test
    fun releaseHistoryFiltersDraftsAndPrereleasesBySourcePolicy() = runBlocking {
        val body = """
            [
              {
                "tag_name":"v3.0.0",
                "name":"Stable 3",
                "published_at":"2026-07-01T12:00:00Z",
                "html_url":"https://github.com/alice/app/releases/tag/v3.0.0",
                "assets":[]
              },
              {
                "tag_name":"v2.0.0-beta",
                "name":"Beta 2",
                "prerelease":true,
                "published_at":"2026-06-01T12:00:00Z",
                "html_url":"https://github.com/alice/app/releases/tag/v2.0.0-beta",
                "assets":[]
              },
              {
                "tag_name":"v1.0.0-draft",
                "draft":true,
                "html_url":"https://github.com/alice/app/releases/tag/v1.0.0-draft",
                "assets":[]
              }
            ]
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val stable = client().listReleaseHistory(
            owner = "alice",
            repo = "app",
            includePrereleases = false,
            sourceKey = "source-a",
        )

        assertEquals(listOf("v3.0.0"), stable.releases.map { it.tagName })
        assertFalse(stable.hasMore)
        assertTrue(server.takeRequest().path.orEmpty().contains("per_page=20&page=1"))

        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val withPrereleases = client().listReleaseHistory(
            owner = "alice",
            repo = "app",
            includePrereleases = true,
            sourceKey = "source-a",
        )

        assertEquals(
            listOf("v3.0.0", "v2.0.0-beta"),
            withPrereleases.releases.map { it.tagName },
        )
        assertTrue(server.takeRequest().path.orEmpty().contains("per_page=20&page=1"))
    }

    @Test
    fun releaseHistoryReportsAContinuationWhenTheBoundedPageIsFull() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    (1..2).joinToString(prefix = "[", postfix = "]") { index ->
                        releaseJson("v$index.0.0")
                    },
                ),
        )

        val result = client().listReleaseHistory(
            owner = "alice",
            repo = "app",
            includePrereleases = true,
            page = 2,
            perPage = 2,
            sourceKey = "source-a",
        )

        assertEquals(2, result.page)
        assertTrue(result.hasMore)
        assertTrue(server.takeRequest().path.orEmpty().contains("per_page=2&page=2"))
    }

    @Test
    fun authenticationAndAuthorizationFailuresAreDistinctAndNeverRetried() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val authentication = runCatching {
            client().listUserRepos("alice", sourceKey = "source-a")
        }.exceptionOrNull()
        assertTrue(authentication is GitHubRequestException)
        assertEquals(
            GitHubFailureKind.Authentication,
            (authentication as GitHubRequestException).kind,
        )

        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "12"))
        val authorization = runCatching {
            client().listUserRepos("alice", sourceKey = "source-a")
        }.exceptionOrNull()
        assertTrue(authorization is GitHubRequestException)
        assertEquals(
            GitHubFailureKind.Authorization,
            (authorization as GitHubRequestException).kind,
        )
        assertEquals(2, server.requestCount)
    }

    @Test
    fun transientServerFailuresUseThreeAttemptBoundedRetry() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(502))
        server.enqueue(MockResponse().setResponseCode(200).setBody(RELEASE))
        val delays = CopyOnWriteArrayList<Long>()
        val client = client(
            retryDelay = { delays += it },
            retryJitterMillis = { 0L },
        )

        val release = client.latestRelease(
            owner = "alice",
            repo = "app",
            includePrereleases = false,
            sourceKey = "source-a",
        )

        assertEquals("v1.2.3", release?.tagName)
        assertEquals(listOf(200L, 400L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun exhaustedServerFailureStopsAfterThreeAttempts() = runBlocking {
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(503))
        }
        val delays = CopyOnWriteArrayList<Long>()

        val failure = runCatching {
            client(
                retryDelay = { delays += it },
                retryJitterMillis = { 0L },
            ).latestRelease(
                owner = "alice",
                repo = "app",
                includePrereleases = false,
                sourceKey = "source-a",
            )
        }.exceptionOrNull()

        assertTrue(failure is GitHubRequestException)
        assertEquals(GitHubFailureKind.Server, (failure as GitHubRequestException).kind)
        assertEquals(listOf(200L, 400L), delays)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun rateLimitHeadersProduceTypedFailureAndResetTime() = runBlocking {
        val resetSeconds = (System.currentTimeMillis() / 1_000L) + 3_600L
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", resetSeconds.toString()),
        )
        val client = client()

        val failure = runCatching {
            client.latestRelease(
                owner = "alice",
                repo = "app",
                includePrereleases = false,
                sourceKey = "source-a",
            )
        }.exceptionOrNull()

        assertTrue(failure is GitHubRequestException)
        failure as GitHubRequestException
        assertEquals(GitHubFailureKind.RateLimited, failure.kind)
        assertEquals(429, failure.statusCode)
        assertEquals(resetSeconds * 1_000L, failure.retryAtEpochMillis)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun testConnectionReportsAuthenticatedOwnerScopesAndRateBudget() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-RateLimit-Remaining", "41")
                .setHeader("X-RateLimit-Reset", "1700000000")
                .setBody("{\"login\":\"alice\"}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-OAuth-Scopes", "repo, read:user")
                .setBody("{\"login\":\"alice\"}"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-RateLimit-Remaining", "39")
                .setHeader("X-RateLimit-Reset", "1700000000")
                .setBody(REPOSITORY_LIST),
        )

        val result = client(pat = "secret-token").testConnection("alice")

        assertEquals("alice", result.authenticatedLogin)
        assertTrue(result.authenticatedOwnerAccess)
        assertEquals(1, result.accessibleRepoCount)
        assertEquals(setOf("repo", "read:user"), result.tokenScopes)
        assertEquals(39L, result.rateLimitRemaining)
        assertEquals(1_700_000_000_000L, result.rateLimitResetEpochMillis)
        assertTrue((1..3).all { server.takeRequest().getHeader("Authorization") == "Bearer secret-token" })
    }

    @Test
    fun testConnectionSurfacesRequiredScopesWhenRepositoryAccessIsRejected() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"login\":\"alice\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"login\":\"token-owner\"}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-Accepted-OAuth-Scopes", "repo")
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader("X-RateLimit-Reset", "1700000000"),
        )

        val failure = runCatching {
            client(pat = "secret-token").testConnection("alice")
        }.exceptionOrNull()

        assertTrue(failure is GitHubRequestException)
        assertEquals(setOf("repo"), (failure as GitHubRequestException).acceptedScopes)
        assertEquals(0L, failure.rateLimitRemaining)
        assertEquals(1_700_000_000_000L, failure.rateLimitResetEpochMillis)
    }

    @Test
    fun exhausted403IsRateLimitRatherThanAuthorizationFailure() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("X-RateLimit-Remaining", "0")
                .setHeader(
                    "X-RateLimit-Reset",
                    ((System.currentTimeMillis() / 1_000L) + 3_600L).toString(),
                ),
        )

        val failure = runCatching {
            client().listUserRepos("alice", sourceKey = "source-a")
        }.exceptionOrNull()

        assertTrue(failure is GitHubRequestException)
        assertEquals(
            GitHubFailureKind.RateLimited,
            (failure as GitHubRequestException).kind,
        )
    }

    @Test
    fun offlinePreflightFailsWithoutOpeningASocketOrRetrying() = runBlocking {
        val delays = CopyOnWriteArrayList<Long>()
        val client = GitHubClient(
            patProvider = { "" },
            logger = null,
            apiBaseUrl = server.url("/").toString().removeSuffix("/"),
            networkAvailable = { false },
            retryDelay = { delays += it },
        )

        val failure = runCatching {
            client.listUserRepos("alice", sourceKey = "source-a")
        }.exceptionOrNull()

        assertTrue(failure is NetworkUnavailableException)
        assertEquals(0, server.requestCount)
        assertTrue(delays.isEmpty())
    }

    @Test
    fun cancellingDownloadCancelsTransportAndRemovesPartialFiles() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("x".repeat(256 * 1024))
                .throttleBody(1_024, 100, TimeUnit.MILLISECONDS),
        )
        val directory = Files.createTempDirectory("las-download-cancel").toFile()
        val target = File(directory, "release.apk")
        val job = launch {
            client().download(server.url("/release.apk").toString(), target) { _, _ -> }
        }
        yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertFalse(target.exists())
        assertFalse(File("${target.absolutePath}.part").exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun resumedDownloadUsesRangeAndRechecksTheCompleteDigest() = runBlocking {
        val prefix = "already-downloaded-"
        val suffix = "remaining-bytes"
        val complete = prefix + suffix
        val partialDirectory = Files.createTempDirectory("las-download-resume-partial").toFile()
        val partial = File(partialDirectory, "asset.part").apply { writeText(prefix) }
        val directory = Files.createTempDirectory("las-download-resume").toFile()
        val target = File(directory, "release.apk")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes ${prefix.length}-${complete.length - 1}/${complete.length}")
                .setBody(suffix),
        )

        client().download(
            url = server.url("/release.apk").toString(),
            target = target,
            expectedDigest = "sha256:${sha256(complete)}",
            partialFile = partial,
        ) { _, _ -> }

        assertEquals("bytes=${prefix.length}-", server.takeRequest().getHeader("Range"))
        assertEquals(complete, target.readText())
        assertFalse(partial.exists())
        partialDirectory.deleteRecursively()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun cancellingResumableDownloadKeepsPartialBytesForTheNextAttempt() = runBlocking {
        val partialDirectory = Files.createTempDirectory("las-download-cancel-resume-partial").toFile()
        val partial = File(partialDirectory, "asset.part").apply { writeText("prefix") }
        val directory = Files.createTempDirectory("las-download-cancel-resume").toFile()
        val target = File(directory, "release.apk")
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setBody("x".repeat(256 * 1024))
                .throttleBody(1_024, 100, TimeUnit.MILLISECONDS),
        )
        val job = launch {
            client().download(
                url = server.url("/release.apk").toString(),
                target = target,
                partialFile = partial,
            ) { _, _ -> }
        }
        yield()
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        job.cancelAndJoin()

        assertFalse(target.exists())
        assertTrue(partial.exists())
        assertTrue(partial.length() >= "prefix".length)
        partialDirectory.deleteRecursively()
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun downloadRejectsUntrustedAssetHostBeforeSendingPat() = runBlocking {
        val directory = Files.createTempDirectory("las-download-host").toFile()
        val target = File(directory, "release.apk")

        val failure = runCatching {
            client(pat = "secret-pat").download(
                url = "https://attacker.invalid/release.apk",
                target = target,
            ) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(target.exists())
        assertEquals(0, server.requestCount)
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun downloadDoesNotSendPatAcrossUntrustedRedirect() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "https://attacker.invalid/release.apk"),
        )
        val directory = Files.createTempDirectory("las-download-redirect").toFile()
        val target = File(directory, "release.apk")

        val failure = runCatching {
            client(pat = "secret-pat").download(
                url = server.url("/release.apk").toString(),
                target = target,
            ) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(1, server.requestCount)
        assertEquals("Bearer secret-pat", server.takeRequest().getHeader("Authorization"))
        assertFalse(target.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun downloadRejectsContentLengthAndCumulativeBytesAboveLimit() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Length", "9")
                .setBody("too-large"),
        )
        val directory = Files.createTempDirectory("las-download-size").toFile()
        val target = File(directory, "release.apk")

        val failure = runCatching {
            client(maxDownloadBytes = 8).download(
                url = server.url("/release.apk").toString(),
                target = target,
            ) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertFalse(target.exists())
        assertFalse(File("${target.absolutePath}.part").exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun downloadAcceptsAssetWhenGitHubSha256DigestMatches() = runBlocking {
        val body = "verified-release"
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val directory = Files.createTempDirectory("las-download-digest-match").toFile()
        val target = File(directory, "release.apk")

        client().download(
            url = server.url("/release.apk").toString(),
            target = target,
            expectedDigest = "sha256:${sha256(body)}",
        ) { _, _ -> }

        assertEquals(body, target.readText())
        assertFalse(File("${target.absolutePath}.part").exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun downloadDeletesArtifactWhenGitHubSha256DigestDoesNotMatch() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("tampered-release"))
        val directory = Files.createTempDirectory("las-download-digest-mismatch").toFile()
        val target = File(directory, "release.apk")

        val failure = runCatching {
            client().download(
                url = server.url("/release.apk").toString(),
                target = target,
                expectedDigest = "sha256:${sha256("expected-release")}",
            ) { _, _ -> }
        }.exceptionOrNull()

        assertTrue(failure is ReleaseAssetDigestMismatchException)
        assertFalse(target.exists())
        assertFalse(File("${target.absolutePath}.part").exists())
        directory.deleteRecursively()
        Unit
    }

    private fun client(
        cache: GitHubResponseCacheStore? = null,
        retryDelay: suspend (Long) -> Unit = {},
        retryJitterMillis: () -> Long = { 0L },
        pat: String = "",
        maxDownloadBytes: Long = 200L * 1024L * 1024L,
    ) = GitHubClient(
        patProvider = { pat },
        logger = null,
        responseCache = cache,
        apiBaseUrl = server.url("/").toString().removeSuffix("/"),
        retryDelay = retryDelay,
        retryJitterMillis = retryJitterMillis,
        maxDownloadBytes = maxDownloadBytes,
    )

    private class MemoryResponseCache : GitHubResponseCacheStore {
        private val values = mutableMapOf<Triple<String, String, String>, CachedGitHubResponse>()

        override fun read(
            sourceKey: String,
            url: String,
            credentialScope: String,
        ): CachedGitHubResponse? = values[Triple(sourceKey, url, credentialScope)]

        override fun write(response: CachedGitHubResponse) {
            values[Triple(response.sourceKey, response.url, response.credentialScope)] = response
        }

        override fun purgeSource(sourceKey: String) {
            values.keys.removeIf { it.first == sourceKey }
        }
    }

    private companion object {
        fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        fun repositoryJson(name: String) = """
            {
              "name":"$name",
              "full_name":"alice/$name",
              "html_url":"https://github.com/alice/$name",
              "owner":{"login":"alice"}
            }
        """.trimIndent()

        fun releaseJson(tagName: String) = """
            {
              "tag_name":"$tagName",
              "html_url":"https://github.com/alice/app/releases/tag/$tagName",
              "assets":[]
            }
        """.trimIndent()

        const val REPOSITORY_LIST = """
            [{
              "name":"app",
              "full_name":"alice/app",
              "html_url":"https://github.com/alice/app",
              "owner":{"login":"alice"}
            }]
        """
        const val RELEASE = """
            {
              "tag_name":"v1.2.3",
              "html_url":"https://github.com/alice/app/releases/tag/v1.2.3",
              "assets":[{
                "name":"app-universal.apk",
                "browser_download_url":"https://example.invalid/app.apk",
                "size":123,
                "content_type":"application/vnd.android.package-archive"
              }]
            }
        """
    }
}
