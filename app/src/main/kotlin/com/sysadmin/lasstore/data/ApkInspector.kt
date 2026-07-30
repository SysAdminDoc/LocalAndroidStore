package com.sysadmin.lasstore.data

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
enum class ApkSignatureScheme {
    V1,
    V2,
    V3,
    V31,
}

@Serializable
data class ApkMetadata(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val signingSha256: String,
    /**
     * SHA-256 of every signing cert in the verified APK Signature Scheme v3/v3.1
     * proof-of-rotation lineage, oldest first. Empty for a signer that has never rotated.
     */
    val lineageSha256: List<String> = emptyList(),
    /** All permissions declared in the APK's manifest, as returned by GET_PERMISSIONS. */
    val requestedPermissions: List<String> = emptyList(),
    /**
     * Schemes that apksig cryptographically verified for this exact APK. An empty set means
     * legacy persisted metadata and is deliberately ineligible for first-pin enrollment.
     */
    val verifiedSignatureSchemes: Set<ApkSignatureScheme> = emptySet(),
) {
    val isEligibleForPinEnrollment: Boolean
        get() =
            verifiedSignatureSchemes.isNotEmpty() &&
                normalizeSigningCertificateSha256(signingSha256) != null &&
                (lineageSha256.isEmpty() ||
                    (
                        lineageSha256.lastOrNull() == signingSha256 &&
                            lineageSha256.distinct().size == lineageSha256.size &&
                            lineageSha256.all { normalizeSigningCertificateSha256(it) != null }
                    ))
}

enum class ApkRejectionReason(
    val userMessage: String,
    val isSignatureFailure: Boolean,
) {
    FILE_UNREADABLE("The downloaded APK cannot be read.", false),
    SIGNATURE_NOT_VERIFIED("APK signature verification failed.", true),
    NO_VERIFIED_SCHEME("The APK has no supported verified signature.", true),
    EMPTY_SIGNER_SET("The APK has no verified publisher certificate.", true),
    MULTIPLE_SIGNERS("APK publisher identity is ambiguous because it has multiple current signers.", true),
    MALFORMED_SIGNER("The APK contains a malformed publisher certificate.", true),
    INVALID_LINEAGE("The APK publisher-key rotation proof is invalid.", true),
    PACKAGE_PARSE_FAILED("The verified file does not contain readable APK package metadata.", false),
    PACKAGE_ID_INVALID("The verified APK has an invalid package identifier.", false),
    PACKAGE_SIGNER_MISMATCH("Android and the APK verifier disagree about the publisher certificate.", true),
}

sealed interface ApkInspectionResult {
    data class Verified(val metadata: ApkMetadata) : ApkInspectionResult

    data class Rejected(
        val reason: ApkRejectionReason,
        /** Stable verifier issue names only; never certificate material or artifact contents. */
        val diagnostics: String,
    ) : ApkInspectionResult
}

internal data class ApkVerificationEvidence(
    val verified: Boolean,
    val schemes: Set<ApkSignatureScheme>,
    val signerSha256: List<String>,
    val lineageSha256: List<String>,
    val errors: List<String> = emptyList(),
)

internal data class ParsedApkPackage(
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long,
    val label: String?,
    val currentSignerSha256: List<String>,
    val requestedPermissions: List<String>,
)

internal fun validateApkEvidence(evidence: ApkVerificationEvidence): ApkRejectionReason? {
    if (!evidence.verified || evidence.errors.isNotEmpty()) {
        return ApkRejectionReason.SIGNATURE_NOT_VERIFIED
    }
    if (evidence.schemes.isEmpty()) return ApkRejectionReason.NO_VERIFIED_SCHEME
    if (evidence.signerSha256.isEmpty()) return ApkRejectionReason.EMPTY_SIGNER_SET
    if (evidence.signerSha256.size != 1) return ApkRejectionReason.MULTIPLE_SIGNERS
    if (evidence.signerSha256.any { normalizeSigningCertificateSha256(it) == null }) {
        return ApkRejectionReason.MALFORMED_SIGNER
    }
    if (evidence.lineageSha256.isEmpty()) return null
    if (ApkSignatureScheme.V3 !in evidence.schemes && ApkSignatureScheme.V31 !in evidence.schemes) {
        return ApkRejectionReason.INVALID_LINEAGE
    }
    if (
        evidence.lineageSha256.any { normalizeSigningCertificateSha256(it) == null } ||
        evidence.lineageSha256.distinct().size != evidence.lineageSha256.size ||
        evidence.lineageSha256.lastOrNull() != evidence.signerSha256.single()
    ) {
        return ApkRejectionReason.INVALID_LINEAGE
    }
    return null
}

internal fun validateParsedPackage(
    parsed: ParsedApkPackage?,
    evidence: ApkVerificationEvidence,
): ApkRejectionReason? {
    if (parsed == null) return ApkRejectionReason.PACKAGE_PARSE_FAILED
    if (!ANDROID_PACKAGE_NAME.matches(parsed.applicationId)) {
        return ApkRejectionReason.PACKAGE_ID_INVALID
    }
    if (
        parsed.currentSignerSha256.size != 1 ||
        parsed.currentSignerSha256.singleOrNull() != evidence.signerSha256.singleOrNull()
    ) {
        return ApkRejectionReason.PACKAGE_SIGNER_MISMATCH
    }
    return null
}

/**
 * Reads APK metadata only after apksig has verified the exact downloaded bytes for every
 * supported Android version (API 26 through the current runtime). PackageManager is then used
 * only for manifest/resources, and its current signer must agree with apksig.
 */
class ApkInspector(private val context: Context) {
    fun inspect(apk: File): ApkMetadata? =
        (inspectResult(apk) as? ApkInspectionResult.Verified)?.metadata

    fun inspectResult(apk: File): ApkInspectionResult {
        if (!apk.isFile || apk.length() <= 0L || !apk.canRead()) {
            return rejected(ApkRejectionReason.FILE_UNREADABLE, "unreadable_file")
        }

        val verifierResult = runCatching {
            ApkVerifier.Builder(apk)
                .setMinCheckedPlatformVersion(Build.VERSION_CODES.O)
                .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT)
                .build()
                .verify()
        }.getOrElse { throwable ->
            return rejected(
                ApkRejectionReason.SIGNATURE_NOT_VERIFIED,
                "verifier_exception:${throwable.javaClass.simpleName}",
            )
        }
        val evidence = verifierResult.toEvidence()
        validateApkEvidence(evidence)?.let { reason ->
            return rejected(reason, evidence.errors.joinToString(",").ifEmpty { reason.name })
        }

        val parsed = parsePackage(apk)
        validateParsedPackage(parsed, evidence)?.let { reason ->
            return rejected(reason, reason.name)
        }
        checkNotNull(parsed)
        val currentSigner = evidence.signerSha256.single()
        val lineage = evidence.lineageSha256.takeIf { it.size > 1 }.orEmpty()
        return ApkInspectionResult.Verified(
            ApkMetadata(
                applicationId = parsed.applicationId,
                versionName = parsed.versionName,
                versionCode = parsed.versionCode,
                label = parsed.label,
                signingSha256 = currentSigner,
                lineageSha256 = lineage,
                requestedPermissions = parsed.requestedPermissions,
                verifiedSignatureSchemes = evidence.schemes,
            ),
        )
    }

    private fun parsePackage(apk: File): ParsedApkPackage? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES or PackageManager.GET_PERMISSIONS
        }
        @Suppress("DEPRECATION")
        val info = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
            ?: return null
        info.applicationInfo?.let { appInfo ->
            appInfo.sourceDir = apk.absolutePath
            appInfo.publicSourceDir = apk.absolutePath
        }
        val label = info.applicationInfo
            ?.let { appInfo -> runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrNull() }
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return ParsedApkPackage(
            applicationId = info.packageName.orEmpty(),
            versionName = info.versionName,
            versionCode = versionCode,
            label = label,
            currentSignerSha256 = currentSigningCertificateSha256(info),
            requestedPermissions = info.requestedPermissions?.distinct().orEmpty(),
        )
    }

    @Suppress("DEPRECATION")
    private fun currentSigningCertificateSha256(info: PackageInfo): List<String> {
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }
        return certificates.map(::sha256Hex)
    }

    private fun ApkVerifier.Result.toEvidence(): ApkVerificationEvidence {
        val schemes = buildSet {
            if (isVerifiedUsingV1Scheme) add(ApkSignatureScheme.V1)
            if (isVerifiedUsingV2Scheme) add(ApkSignatureScheme.V2)
            if (isVerifiedUsingV3Scheme) add(ApkSignatureScheme.V3)
            if (isVerifiedUsingV31Scheme) add(ApkSignatureScheme.V31)
        }
        return ApkVerificationEvidence(
            verified = isVerified,
            schemes = schemes,
            signerSha256 = signerCertificates.map { sha256Hex(it.encoded) },
            lineageSha256 = signingCertificateLineage
                ?.certificatesInLineage
                ?.map { sha256Hex(it.encoded) }
                .orEmpty(),
            errors = allErrors.map { it.issue.name }.distinct().sorted(),
        )
    }

    private fun rejected(reason: ApkRejectionReason, diagnostics: String) =
        ApkInspectionResult.Rejected(reason, diagnostics)
}

internal fun normalizeSigningCertificateSha256(value: String): String? =
    value.trim().lowercase().takeIf { SIGNING_SHA256.matches(it) }

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private val SIGNING_SHA256 = Regex("^[0-9a-f]{64}$")
private val ANDROID_PACKAGE_NAME =
    Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$")
