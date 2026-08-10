package com.sysadmin.lasstore.install

import android.content.pm.PackageInstaller
import com.sysadmin.lasstore.data.GitHubFailureKind
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.ReleaseAssetDigestMismatchException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedUpdateFailureClassifierTest {
    @Test
    fun retriesOnlyTransientTransportFailures() {
        val failures = listOf(
            QueuedUpdateFailureClassifier.fromThrowable(UnknownHostException("offline")),
            QueuedUpdateFailureClassifier.fromThrowable(SocketTimeoutException("slow")),
            QueuedUpdateFailureClassifier.fromThrowable(
                GitHubRequestException(
                    kind = GitHubFailureKind.RateLimited,
                    statusCode = 429,
                    retryAtEpochMillis = 1234L,
                    message = "limited",
                )
            ),
            QueuedUpdateFailureClassifier.fromThrowable(
                GitHubRequestException(
                    kind = GitHubFailureKind.Server,
                    statusCode = 503,
                    message = "unavailable",
                )
            ),
        )

        assertTrue(failures.all { it.retryable })
        assertEquals(1234L, failures[2].retryAtEpochMillis)
    }

    @Test
    fun authenticationAndRiskDecisionsAreTerminal() {
        val auth = QueuedUpdateFailureClassifier.fromThrowable(
            GitHubRequestException(
                kind = GitHubFailureKind.Authentication,
                statusCode = 401,
                message = "bad token",
            )
        )
        val cancelled = QueuedUpdateFailureClassifier.fromInstaller(
            PackageInstaller.STATUS_FAILURE_ABORTED,
            "cancelled",
        )
        val incompatible = QueuedUpdateFailureClassifier.fromInstaller(
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            "incompatible",
        )

        assertFalse(auth.retryable)
        assertEquals(QueuedUpdateFailureKind.Authentication, auth.kind)
        assertFalse(cancelled.retryable)
        assertEquals(QueuedUpdateFailureKind.UserCancelled, cancelled.kind)
        assertFalse(incompatible.retryable)
        assertEquals(QueuedUpdateFailureKind.Incompatible, incompatible.kind)
    }

    @Test
    fun installerTimeoutRemainsBoundedRetryEligible() {
        val timeout = QueuedUpdateFailureClassifier.fromInstaller(
            PackageInstaller.STATUS_FAILURE_TIMEOUT,
            "timed out",
        )

        assertTrue(timeout.retryable)
        assertEquals(QueuedUpdateFailureKind.Timeout, timeout.kind)
    }

    @Test
    fun assetDigestFailureIsTerminalInvalidArtifact() {
        val failure = QueuedUpdateFailureClassifier.fromThrowable(
            ReleaseAssetDigestMismatchException(
                expectedDigest = "expected",
                actualDigest = "actual",
            )
        )

        assertFalse(failure.retryable)
        assertEquals(QueuedUpdateFailureKind.InvalidArtifact, failure.kind)
    }
}
