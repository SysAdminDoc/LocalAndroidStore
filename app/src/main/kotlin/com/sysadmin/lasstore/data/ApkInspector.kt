package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

data class ApkMetadata(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val signingSha256: String,
    /**
     * SHA-256 of every signing cert in the APK's v3 signing-cert lineage, oldest first.
     * Empty if no v3 lineage (single-key APK). The current cert ([signingSha256]) is always
     * the last entry when [lineageSha256] is non-empty.
     */
    val lineageSha256: List<String> = emptyList(),
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
        val lineage = readLineageSha256(apk)
        return ApkMetadata(
            applicationId = info.packageName,
            versionName = info.versionName,
            versionCode = versionCode,
            label = label,
            signingSha256 = sigSha256,
            lineageSha256 = lineage,
        )
    }

    /**
     * Read the APK Signature Scheme v3 / v3.1 cert lineage via apksig.
     *
     * Returns the SHA-256 fingerprints of every cert in the lineage, oldest first. An empty
     * list means either (a) no v3 lineage in the APK (single-key, never rotated) or (b) the
     * APK doesn't pass v2/v3 verification at all.
     *
     * Use this to allow legitimate publisher key rotations: if our pinned cert SHA-256 appears
     * anywhere in the new APK's lineage and the new cert is signed by an earlier lineage entry,
     * accept the install. The platform itself enforces "new cert was signed by previous" — we
     * just need to surface the chain to the pin-store.
     */
    private fun readLineageSha256(apk: File): List<String> = try {
        val result = ApkVerifier.Builder(apk).build().verify()
        val lineage = result.signingCertificateLineage ?: return emptyList()
        lineage.certificatesInLineage.map { cert -> sha256Hex(cert.encoded) }
    } catch (t: Throwable) {
        // apksig throws on unsigned / malformed / v1-only APKs — fine, treat as no lineage.
        emptyList()
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
