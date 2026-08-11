package com.sysadmin.lasstore.domain

/**
 * LocalAndroidStore's source-evidence status. This is deliberately separate from Google's
 * developer-registration status, which only Android's system verifier can determine.
 */
enum class SourceVerificationStatus {
    Verified,
    Unverified,
    Unknown,
}

internal fun sourceVerificationStatus(
    applicationId: String?,
    knownSignerSha256: String?,
    pinnedSignerSha256: String?,
): SourceVerificationStatus {
    if (applicationId.isNullOrBlank()) return SourceVerificationStatus.Unknown
    if (pinnedSignerSha256.isNullOrBlank()) return SourceVerificationStatus.Unverified
    if (knownSignerSha256.isNullOrBlank()) return SourceVerificationStatus.Unknown
    return if (knownSignerSha256.equals(pinnedSignerSha256, ignoreCase = true)) {
        SourceVerificationStatus.Verified
    } else {
        SourceVerificationStatus.Unverified
    }
}
