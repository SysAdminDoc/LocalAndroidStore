package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

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
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val fileLock = Any()

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
        val assetDigest: String = "",
        val verifiedLineageSha256: List<String> = emptyList(),
        val verifiedSignatureSchemes: List<String> = emptyList(),
        val reason: String = "",
        val message: String = "",
    )

    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun installSucceeded(info: AppInfo, meta: ApkMetadata) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "install_ok",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
        ))

    /** Durable outbox marker written before install trust/cache state is changed. */
    fun installSuccessPending(info: AppInfo, meta: ApkMetadata): Boolean = append(
        Entry(
            ts = System.currentTimeMillis(),
            event = "install_success_pending",
            applicationId = meta.applicationId,
            source = info.handle,
            tagName = info.tagName,
            versionName = meta.versionName,
            versionCode = meta.versionCode,
            certSha256 = meta.signingSha256,
            reason = "state_transition_pending_audit_completion",
        ),
    )

    fun installBlocked(info: AppInfo, meta: ApkMetadata, reason: String) =
        append(Entry(
            ts = System.currentTimeMillis(), event = "install_blocked",
            applicationId = meta.applicationId, source = info.handle,
            tagName = info.tagName, versionName = meta.versionName,
            versionCode = meta.versionCode, certSha256 = meta.signingSha256,
            reason = reason,
        ))

    fun historicalReleaseSelected(info: AppInfo): Boolean = append(
        Entry(
            ts = System.currentTimeMillis(),
            event = "historical_release_selected",
            applicationId = info.applicationId.orEmpty(),
            source = info.handle,
            tagName = info.tagName,
            assetDigest = info.asset.digest.orEmpty(),
            reason = "explicit_foreground_release_selection",
        ),
    )

    fun externalAppObserved(info: AppInfo, meta: ApkMetadata, installed: InstalledInfo): Boolean =
        append(
            Entry(
                ts = System.currentTimeMillis(),
                event = "external_app_observed",
                applicationId = installed.applicationId,
                source = info.handle,
                tagName = info.tagName,
                versionName = installed.versionName,
                versionCode = installed.versionCode,
                certSha256 = meta.signingSha256,
                installedCertSha256 = installed.currentSignerSha256.orEmpty(),
                reason = "installed_before_local_android_store_adoption",
            ),
        )

    fun externalAppAdoptionPending(info: AppInfo, installed: InstalledInfo): Boolean =
        append(
            Entry(
                ts = System.currentTimeMillis(),
                event = "external_app_adoption_pending",
                applicationId = installed.applicationId,
                source = info.handle,
                tagName = info.tagName,
                versionName = installed.versionName,
                versionCode = installed.versionCode,
                certSha256 = installed.currentSignerSha256.orEmpty(),
                installedCertSha256 = installed.currentSignerSha256.orEmpty(),
                reason = "user_confirmed_adoption_state_transition_pending",
            ),
        )

    fun externalAppAdopted(info: AppInfo, installed: InstalledInfo): Boolean =
        append(
            Entry(
                ts = System.currentTimeMillis(),
                event = "external_app_adopted",
                applicationId = installed.applicationId,
                source = info.handle,
                tagName = info.tagName,
                versionName = installed.versionName,
                versionCode = installed.versionCode,
                certSha256 = installed.currentSignerSha256.orEmpty(),
                installedCertSha256 = installed.currentSignerSha256.orEmpty(),
                reason = "user_confirmed_external_provenance",
            ),
        )

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

    /** Durable outbox marker written before a publisher pin replacement is applied. */
    fun publisherPinReplacementPending(
        info: AppInfo,
        meta: ApkMetadata,
        previousPinSha256: String,
        installedSignerSha256: String?,
    ): Boolean = append(
        Entry(
            ts = System.currentTimeMillis(),
            event = "publisher_pin_replacement_pending",
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
            reason = "state_transition_pending_audit_completion",
        ),
    )

    fun clear() {
        synchronized(fileLock) {
            file.delete()
            rotated.delete()
        }
        _entries.value = emptyList()
    }

    private fun append(entry: Entry): Boolean {
        val written = runCatching {
            val line = json.encodeToString(entry) + "\n"
            synchronized(fileLock) {
                if (file.length() + line.toByteArray().size > MAX_BYTES) {
                    rotated.delete()
                    if (!file.renameTo(rotated)) {
                        file.writeText("")
                    }
                }
                FileOutputStream(file, true).use { output ->
                    output.write(line.toByteArray())
                    output.flush()
                    output.fd.sync()
                }
            }
        }.isSuccess
        if (written) {
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
        return written
    }

    private fun readEntries(): List<Entry> =
        listOf(rotated, file)
            .flatMap { source ->
                runCatching {
                    if (!source.isFile) {
                        emptyList()
                    } else {
                        source.useLines { lines ->
                            lines.mapNotNull { line ->
                                runCatching { json.decodeFromString<Entry>(line) }.getOrNull()
                            }.toList()
                        }
                    }
                }.getOrDefault(emptyList())
            }
            .takeLast(MAX_ENTRIES)

    private companion object {
        const val MAX_BYTES = 256L * 1024L
        const val MAX_ENTRIES = 500
    }
}
