package com.sysadmin.lasstore.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
        assertNotNull(cache.read("source-a", server.url("/users/alice/repos?per_page=100&type=owner&sort=updated&page=1").toString()))
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

    private fun client(
        cache: GitHubResponseCacheStore? = null,
        retryDelay: suspend (Long) -> Unit = {},
        retryJitterMillis: () -> Long = { 0L },
    ) = GitHubClient(
        patProvider = { "" },
        logger = null,
        responseCache = cache,
        apiBaseUrl = server.url("/").toString().removeSuffix("/"),
        retryDelay = retryDelay,
        retryJitterMillis = retryJitterMillis,
    )

    private class MemoryResponseCache : GitHubResponseCacheStore {
        private val values = mutableMapOf<Pair<String, String>, CachedGitHubResponse>()

        override fun read(sourceKey: String, url: String): CachedGitHubResponse? =
            values[sourceKey to url]

        override fun write(response: CachedGitHubResponse) {
            values[response.sourceKey to response.url] = response
        }
    }

    private companion object {
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
