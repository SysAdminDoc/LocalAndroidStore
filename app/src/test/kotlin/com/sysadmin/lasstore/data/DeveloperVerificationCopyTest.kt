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

        assertTrue(notice.body.contains("this region"))
        assertTrue(notice.body.contains("September 2026"))
        assertTrue(notice.body.contains("com.example.app"))
    }

    @Test
    fun nonRegionalCountriesGetGlobalRolloutCopy() {
        val notice = DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = "com.example.app",
            surface = DeveloperVerificationSurface.GooglePlayServices,
            countryCode = "US",
        )

        assertTrue(notice.body.contains("rolls out globally in 2027"))
        assertTrue(notice.body.contains("advanced flow or ADB"))
    }
}
