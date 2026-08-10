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
