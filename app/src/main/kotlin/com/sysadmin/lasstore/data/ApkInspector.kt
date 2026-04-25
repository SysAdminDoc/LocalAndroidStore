package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkMetadata(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val signingSha256: String,
)

/**
 * Light-weight APK metadata reader. Uses PackageManager#getPackageArchiveInfo so we don't
 * have to ship a full AXML/ARSC parser. The APK does NOT need to be installed; this works
 * on any APK on disk, including the freshly-downloaded one in app cache.
 */
class ApkInspector(private val context: Context) {
    fun inspect(apk: File): ApkMetadata? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return null
        info.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = apk.absolutePath
            appInfo.publicSourceDir = apk.absolutePath
        }
        val label = info.applicationInfo?.let { pm.getApplicationLabel(it).toString() }
        val versionCode: Long = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        val sigSha256 = signingCertSha256(info) ?: ""
        return ApkMetadata(
            applicationId = info.packageName,
            versionName = info.versionName,
            versionCode = versionCode,
            label = label,
            signingSha256 = sigSha256,
        )
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256(info: android.content.pm.PackageInfo): String? {
        val cert = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val si = info.signingInfo ?: return null
            val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            arr?.firstOrNull()?.toByteArray()
        } else {
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        return sha256Hex(cert)
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
