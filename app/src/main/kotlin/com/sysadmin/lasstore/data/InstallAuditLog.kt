package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk JSON-Lines record of every install / uninstall / signature-block event.
 *
 * Lives at <files>/logs/install.log. Rotates to install.log.1 at 256 KB. Local only —
 * never leaves the device. Useful as a forensic surface ("what did LAS install and when?")
 * and a debugging trail when a user reports a stuck card.
 */
class InstallAuditLog(context: Context) {
    private val dir = File(context.filesDir, "logs").apply { mkdirs() }
    private val file = File(dir, "install.log")
    private val rotated = File(dir, "install.log.1")
    private val json = Json { encodeDefaults = true }

    @Serializable
    data class Entry(
        val ts: Long,
        val event: String,
        val applicationId: String,
        val source: String,                 // owner/repo
        val tagName: String,
        val versionName: String? = null,
        val versionCode: Long? = null,
        val certSha256: String = "",
        val previousCertSha256: String = "",
        val installedCertSha256: String = "",
        val verifiedLineageSha256: List<String> = emptyList(),
        val verifiedSignatureSchemes: List<String> = emptyList(),
        val reason: String = "",
        val message: String = "",
    )

    fun installSucceeded(info: AppInfo, meta: ApkMetadata) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "install_ok",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
        ))

    fun installBlocked(info: AppInfo, meta: ApkMetadata, reason: String) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "install_blocked",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
            reason = reason,
        ))

    fun installFailed(info: AppInfo, meta: ApkMetadata, message: String) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "install_failed",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
            message = message,
        ))

    fun developerVerificationWarned(info: AppInfo, meta: ApkMetadata, reason: String) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "developer_verification_warned",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
            reason = reason,
        ))

    fun uninstallInitiated(applicationId: String, source: String) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "uninstall_initiated",
            applicationId = applicationId, source = source, tagName = "",
        ))

    fun publisherPinRecoveryAuthorized(
        info: AppInfo,
        meta: ApkMetadata,
        previousPinSha256: String,
        installedSignerSha256: String?,
    ): Boolean = append(
        Entry(
            ts = System.currentTimeMillis(),
            event = "publisher_pin_recovery_authorized",
            applicationId = meta.applicationId,
            source = info.handle,
            tagName = info.tagName,
            versionName = meta.versionName,
            versionCode = meta.versionCode,
            certSha256 = meta.signingSha256,
            previousCertSha256 = previousPinSha256,
            installedCertSha256 = installedSignerSha256.orEmpty(),
            verifiedLineageSha256 = meta.lineageSha256,
            verifiedSignatureSchemes = meta.verifiedSignatureSchemes.map { it.name }.sorted(),
            reason = "typed_package_plus_second_acknowledgement",
        ),
    )

    fun publisherPinReplaced(
        info: AppInfo,
        meta: ApkMetadata,
        previousPinSha256: String,
        installedSignerSha256: String?,
    ): Boolean = append(
        Entry(
            ts = System.currentTimeMillis(),
            event = "publisher_pin_replaced",
            applicationId = meta.applicationId,
            source = info.handle,
            tagName = info.tagName,
            versionName = meta.versionName,
            versionCode = meta.versionCode,
            certSha256 = meta.signingSha256,
            previousCertSha256 = previousPinSha256,
            installedCertSha256 = installedSignerSha256.orEmpty(),
            verifiedLineageSha256 = meta.lineageSha256,
            verifiedSignatureSchemes = meta.verifiedSignatureSchemes.map { it.name }.sorted(),
            reason = "manual_trust_recovery",
        ),
    )

    private fun append(entry: Entry): Boolean =
        runCatching {
            file.appendText(json.encodeToString(entry) + "\n")
            if (file.length() > MAX_BYTES) {
                rotated.delete()
                file.renameTo(rotated)
            }
        }.isSuccess

    private companion object { const val MAX_BYTES = 256L * 1024L }
}
