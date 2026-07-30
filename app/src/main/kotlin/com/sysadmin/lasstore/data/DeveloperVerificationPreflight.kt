package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

data class DeveloperVerificationNotice(
    val title: String,
    val body: String,
    val reason: String,
    val surface: DeveloperVerificationSurface,
    val registrationStatus: DeveloperRegistrationStatus,
    val initialEnforcementScope: InitialEnforcementScope,
    val guidanceUrl: String,
)

class DeveloperVerificationPreflight(private val context: Context) {
    fun evaluate(meta: ApkMetadata): DeveloperVerificationNotice {
        val surface = detectSurface()
        return DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = meta.applicationId,
            surface = surface,
            countryCode = Locale.getDefault().country,
        )
    }

    private fun detectSurface(): DeveloperVerificationSurface {
        val pm = context.packageManager
        if (pm.isEnabledPackage(DEVELOPER_VERIFIER_PACKAGE)) {
            return DeveloperVerificationSurface.AndroidDeveloperVerifier
        }
        if (pm.isEnabledPackage(GOOGLE_PLAY_SERVICES_PACKAGE)) {
            return DeveloperVerificationSurface.GooglePlayServices
        }
        return DeveloperVerificationSurface.NotDetected
    }

    private fun PackageManager.isEnabledPackage(packageName: String): Boolean =
        packageInfo(packageName)
            ?.applicationInfo
            ?.enabled == true

    private fun PackageManager.packageInfo(packageName: String): PackageInfo? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                getPackageInfo(packageName, 0)
            }
        }.getOrNull()

    private companion object {
        private const val DEVELOPER_VERIFIER_PACKAGE = "com.google.android.verifier"
        private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
    }
}

enum class DeveloperVerificationSurface {
    AndroidDeveloperVerifier,
    GooglePlayServices,
    NotDetected,
}

enum class DeveloperRegistrationStatus { Unknown }

enum class InitialEnforcementScope { NotApplicableToIndependentSideload }

internal object DeveloperVerificationCopy {
    private val regionalCountries = setOf("BR", "ID", "SG", "TH")

    fun unknownRegistrationNotice(
        applicationId: String,
        surface: DeveloperVerificationSurface,
        countryCode: String,
    ): DeveloperVerificationNotice {
        val normalizedCountry = countryCode.trim().uppercase(Locale.US)
        val localeCopy = if (normalizedCountry in regionalCountries) {
            "The current locale country is one of the four initial regions."
        } else {
            "The current locale country is outside the four initial regions."
        }
        val surfaceCopy = when (surface) {
            DeveloperVerificationSurface.AndroidDeveloperVerifier ->
                "Android Developer Verifier is present on this device."
            DeveloperVerificationSurface.GooglePlayServices ->
                "Google verification services are present on this device."
            DeveloperVerificationSurface.NotDetected ->
                "A Google developer-verification surface was not detected on this device."
        }
        return DeveloperVerificationNotice(
            title = "Verification registration: Unknown",
            body = "$surfaceCopy Registration status for $applicationId is Unknown because " +
                "Android exposes no registration-status capability to LocalAndroidStore. " +
                "$localeCopy LocalAndroidStore uses direct independent sideloading, which " +
                "Google's FAQ says is not in scope for the initial participating-store " +
                "enforcement beginning 2026-09-30. Global rollout begins in 2027; its exact " +
                "date and this route's future behavior are not yet published.",
            reason = "surface=${surface.name};registration=Unknown;" +
                "route=IndependentSideload;initialScope=NotApplicable",
            surface = surface,
            registrationStatus = DeveloperRegistrationStatus.Unknown,
            initialEnforcementScope = InitialEnforcementScope.NotApplicableToIndependentSideload,
            guidanceUrl = OFFICIAL_GUIDANCE_URL,
        )
    }

    const val OFFICIAL_GUIDANCE_URL =
        "https://developer.android.com/developer-verification/guides/faq"
}
