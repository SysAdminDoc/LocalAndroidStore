package com.sysadmin.lasstore.data

import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperVerificationCopyTest {
    @Test
    fun regionalCountriesGetRegionalDeadlineCopy() {
        val notice = DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = "com.example.app",
            surface = DeveloperVerificationSurface.AndroidDeveloperVerifier,
            countryCode = "br",
        )

        assertTrue(notice.body.contains("one of the four initial regions"))
        assertTrue(notice.body.contains("2026-09-30"))
        assertTrue(notice.body.contains("com.example.app"))
        assertTrue(notice.body.contains("not in scope"))
        assertTrue(notice.body.contains("Registration status"))
        assertTrue(notice.body.contains("Unknown"))
        assertTrue(notice.guidanceUrl.startsWith("https://developer.android.com/"))
    }

    @Test
    fun nonRegionalCountriesGetGlobalRolloutCopy() {
        val notice = DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = "com.example.app",
            surface = DeveloperVerificationSurface.GooglePlayServices,
            countryCode = "US",
        )

        assertTrue(notice.body.contains("outside the four initial regions"))
        assertTrue(notice.body.contains("Global rollout begins in 2027"))
        assertTrue(notice.body.contains("direct independent sideloading"))
        assertTrue(notice.body.contains("not in scope"))
        assertTrue(notice.registrationStatus == DeveloperRegistrationStatus.Unknown)
        assertTrue(
            notice.initialEnforcementScope ==
                InitialEnforcementScope.NotApplicableToIndependentSideload
        )
    }

    @Test
    fun missingSurfaceDoesNotBecomeARegistrationClaim() {
        val notice = DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = "com.example.app",
            surface = DeveloperVerificationSurface.NotDetected,
            countryCode = "US",
        )

        assertTrue(notice.body.contains("surface was not detected"))
        assertTrue(notice.body.contains("Registration status"))
        assertTrue(notice.body.contains("Unknown"))
    }
}
