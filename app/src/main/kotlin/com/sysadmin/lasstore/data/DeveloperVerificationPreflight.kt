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
)

class DeveloperVerificationPreflight(private val context: Context) {
    fun evaluate(meta: ApkMetadata): DeveloperVerificationNotice? {
        val surface = detectSurface() ?: return null
        return DeveloperVerificationCopy.unknownRegistrationNotice(
            applicationId = meta.applicationId,
            surface = surface,
            countryCode = Locale.getDefault().country,
        )
    }

    private fun detectSurface(): DeveloperVerificationSurface? {
        val pm = context.packageManager
        if (pm.isEnabledPackage(DEVELOPER_VERIFIER_PACKAGE)) {
            return DeveloperVerificationSurface.AndroidDeveloperVerifier
        }
        if (pm.isEnabledPackage(GOOGLE_PLAY_SERVICES_PACKAGE)) {
            return DeveloperVerificationSurface.GooglePlayServices
        }
        return null
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
}

internal object DeveloperVerificationCopy {
    private val regionalCountries = setOf("BR", "ID", "SG", "TH")

    fun unknownRegistrationNotice(
        applicationId: String,
        surface: DeveloperVerificationSurface,
        countryCode: String,
    ): DeveloperVerificationNotice {
        val normalizedCountry = countryCode.trim().uppercase(Locale.US)
        val rollout = if (normalizedCountry in regionalCountries) {
            "Developer verification enforcement starts in this region in September 2026."
        } else {
            "Developer verification starts in Brazil, Indonesia, Singapore, and Thailand in " +
                "September 2026, then rolls out globally in 2027."
        }
        val surfaceCopy = when (surface) {
            DeveloperVerificationSurface.AndroidDeveloperVerifier ->
                "Android Developer Verifier is present on this device."
            DeveloperVerificationSurface.GooglePlayServices ->
                "Google verification services are present on this device."
        }
        return DeveloperVerificationNotice(
            title = "Developer verification unknown",
            body = "$surfaceCopy $rollout LocalAndroidStore cannot publicly query whether " +
                "$applicationId is registered with Google yet. If its package name and " +
                "signing key are not registered when enforcement applies, installs or " +
                "updates may require Android's advanced flow or ADB.",
            reason = surface.name,
        )
    }
}
