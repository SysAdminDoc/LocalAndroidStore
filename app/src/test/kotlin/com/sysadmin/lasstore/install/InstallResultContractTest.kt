package com.sysadmin.lasstore.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallResultContractTest {
    private val registration = InstallResultRegistration(
        capability = "a".repeat(64),
        sessionId = 42,
        applicationId = "com.example.app",
        route = InstallResultRoute.Foreground,
    )

    @Test
    fun acceptsMatchingTerminalResult() {
        val result = InstallResultValidator.validate(
            registration,
            envelope(
                status = PackageInstaller.STATUS_SUCCESS,
                platformSessionId = 42,
                platformApplicationId = "com.example.app",
            ),
        )

        assertEquals(
            InstallResultValidation.Accepted(registration, terminal = true),
            result,
        )
    }

    @Test
    fun pendingUserActionIsNotTerminal() {
        val result = InstallResultValidator.validate(
            registration,
            envelope(status = PackageInstaller.STATUS_PENDING_USER_ACTION),
        )

        assertEquals(
            InstallResultValidation.Accepted(registration, terminal = false),
            result,
        )
    }

    @Test
    fun rejectsMismatchedPlatformSession() {
        val result = InstallResultValidator.validate(
            registration,
            envelope(
                status = PackageInstaller.STATUS_SUCCESS,
                platformSessionId = 43,
            ),
        )

        assertRejected(result, "platform session mismatch")
    }

    @Test
    fun rejectsMismatchedPlatformPackage() {
        val result = InstallResultValidator.validate(
            registration,
            envelope(
                status = PackageInstaller.STATUS_SUCCESS,
                platformApplicationId = "com.attacker.app",
            ),
        )

        assertRejected(result, "platform package mismatch")
    }

    @Test
    fun rejectsMismatchedForegroundOperationGeneration() {
        val generationBound = registration.copy(operationId = "current-operation")

        val result = InstallResultValidator.validate(
            generationBound,
            InstallResultEnvelope(
                capability = generationBound.capability,
                declaredSessionId = generationBound.sessionId,
                declaredApplicationId = generationBound.applicationId,
                declaredOperationId = "stale-operation",
                platformSessionId = generationBound.sessionId,
                platformApplicationId = generationBound.applicationId,
                status = PackageInstaller.STATUS_SUCCESS,
            ),
        )

        assertRejected(result, "declared operation mismatch")
    }

    @Test
    fun rejectsUnknownStatusAndConsumedCapability() {
        assertRejected(
            InstallResultValidator.validate(registration, envelope(status = 9_999)),
            "unknown status",
        )
        assertRejected(
            InstallResultValidator.validate(null, envelope(status = PackageInstaller.STATUS_SUCCESS)),
            "already-consumed",
        )
    }

    private fun envelope(
        status: Int,
        platformSessionId: Int? = null,
        platformApplicationId: String? = null,
        declaredOperationId: String? = registration.operationId,
    ) = InstallResultEnvelope(
        capability = registration.capability,
        declaredSessionId = registration.sessionId,
        declaredApplicationId = registration.applicationId,
        declaredOperationId = declaredOperationId,
        platformSessionId = platformSessionId,
        platformApplicationId = platformApplicationId,
        status = status,
    )

    private fun assertRejected(result: InstallResultValidation, reason: String) {
        assertTrue(result is InstallResultValidation.Rejected)
        assertTrue((result as InstallResultValidation.Rejected).reason.contains(reason))
    }
}
