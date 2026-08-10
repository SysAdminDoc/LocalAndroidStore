package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

data class InstalledInfo(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val currentSignerSha256: String? = null,
)

/** A stored pin is a hard boundary: an unavailable or different current signer is not trusted. */
internal fun signerMatchesPin(currentSignerSha256: String?, pinnedSignerSha256: String?): Boolean =
    pinnedSignerSha256.isNullOrBlank() || currentSignerSha256 == pinnedSignerSha256

/** Recovered installs must have an observed signer for the exact verified APK metadata. */
internal fun signerMatchesVerifiedArtifact(
    currentSignerSha256: String?,
    expectedSignerSha256: String,
): Boolean = expectedSignerSha256.isNotBlank() && currentSignerSha256 == expectedSignerSha256

class InstallStateRepo(private val context: Context) {
    fun isInstalled(applicationId: String): Boolean = info(applicationId) != null

    fun info(applicationId: String): InstalledInfo? {
        val pm = context.packageManager
        return runCatching {
            val pkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    applicationId,
                    PackageManager.PackageInfoFlags.of(
                        PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
                    ),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    applicationId,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        PackageManager.GET_SIGNATURES
                    },
                )
            }
            val vc: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.longVersionCode
            } else {
                @Suppress("DEPRECATION") pkg.versionCode.toLong()
            }
            val currentSigners = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pkg.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }.orEmpty()
            } else {
                @Suppress("DEPRECATION")
                pkg.signatures?.map { sha256Hex(it.toByteArray()) }.orEmpty()
            }
            InstalledInfo(
                applicationId = applicationId,
                versionName = pkg.versionName,
                versionCode = vc,
                currentSignerSha256 = currentSigners.singleOrNull(),
            )
        }.getOrNull()
    }
}
