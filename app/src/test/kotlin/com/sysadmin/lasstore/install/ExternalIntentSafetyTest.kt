package com.sysadmin.lasstore.install

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExternalIntentSafetyTest {
    @Test
    fun unresolvedExternalIntentIsReportedWithoutStarting() {
        var started = false

        val result = safeLaunchExternalIntent(
            intent = Intent(Intent.ACTION_VIEW),
            canResolve = { false },
            start = { started = true },
            failureMessage = "Could not open link.",
        )

        assertTrue(result is ExternalLaunchResult.Failed)
        assertEquals(
            "Could not open link. No compatible Android activity is available.",
            (result as ExternalLaunchResult.Failed).message,
        )
        assertTrue(!started)
    }

    @Test
    fun platformLaunchFailuresBecomeActionableResults() {
        val result = safeLaunchExternalIntent(
            intent = Intent(Intent.ACTION_VIEW),
            canResolve = { true },
            start = { throw SecurityException("denied") },
            failureMessage = "Could not open link.",
        )

        assertEquals(
            "Could not open link. Android denied the request.",
            (result as ExternalLaunchResult.Failed).message,
        )
    }

    @Test
    fun successfulExternalLaunchReportsStarted() {
        val result = safeLaunchExternalIntent(
            intent = Intent(Intent.ACTION_VIEW),
            canResolve = { true },
            start = {},
            failureMessage = "Could not open link.",
        )

        assertEquals(ExternalLaunchResult.Started, result)
    }
}
