package com.sysadmin.lasstore.install

import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.pm.PackageInstaller
import com.sysadmin.lasstore.data.ApkInspectionResult
import com.sysadmin.lasstore.data.GitHubFailureKind
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.ServiceLocator
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface QueuedUpdateResult {
    data object Installed : QueuedUpdateResult
    data class Queued(val sessionId: Int) : QueuedUpdateResult
    data class Failed(
        val message: String,
        val kind: QueuedUpdateFailureKind,
        val retryAtEpochMillis: Long? = null,
    ) : QueuedUpdateResult {
        val retryable: Boolean
            get() = kind == QueuedUpdateFailureKind.Network ||
                kind == QueuedUpdateFailureKind.Timeout ||
                kind == QueuedUpdateFailureKind.RateLimited ||
                kind == QueuedUpdateFailureKind.Server
    }
}

internal object QueuedUpdateFailureClassifier {
    fun fromThrowable(throwable: Throwable): QueuedUpdateResult.Failed = when (throwable) {
        is GitHubRequestException -> {
            val kind = when (throwable.kind) {
                GitHubFailureKind.Authentication -> QueuedUpdateFailureKind.Authentication
                GitHubFailureKind.Authorization -> QueuedUpdateFailureKind.Authorization
                GitHubFailureKind.RateLimited -> QueuedUpdateFailureKind.RateLimited
                GitHubFailureKind.Server -> QueuedUpdateFailureKind.Server
                GitHubFailureKind.Http -> QueuedUpdateFailureKind.InvalidArtifact
            }
            QueuedUpdateResult.Failed(
                message = throwable.message ?: "GitHub request failed",
                kind = kind,
                retryAtEpochMillis = throwable.retryAtEpochMillis,
            )
        }
        is SocketTimeoutException ->
            QueuedUpdateResult.Failed("Network request timed out.", QueuedUpdateFailureKind.Timeout)
        is UnknownHostException,
        is ConnectException,
        is NoRouteToHostException,
        is IOException ->
            QueuedUpdateResult.Failed(
                throwable.message ?: "Network unavailable.",
                QueuedUpdateFailureKind.Network,
            )
        is SecurityException ->
            QueuedUpdateResult.Failed(
                throwable.message ?: "Android policy blocked the update.",
                QueuedUpdateFailureKind.Policy,
            )
        else ->
            QueuedUpdateResult.Failed(
                throwable.message ?: "Queued update failed.",
                QueuedUpdateFailureKind.Unknown,
            )
    }

    fun fromInstaller(status: Int?, message: String): QueuedUpdateResult.Failed {
        val kind = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> QueuedUpdateFailureKind.UserCancelled
            PackageInstaller.STATUS_FAILURE_BLOCKED -> QueuedUpdateFailureKind.Policy
            PackageInstaller.STATUS_FAILURE_CONFLICT -> QueuedUpdateFailureKind.Signature
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> QueuedUpdateFailureKind.Incompatible
            PackageInstaller.STATUS_FAILURE_INVALID -> QueuedUpdateFailureKind.InvalidArtifact
            PackageInstaller.STATUS_FAILURE_STORAGE -> QueuedUpdateFailureKind.Storage
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> QueuedUpdateFailureKind.Timeout
            else -> QueuedUpdateFailureKind.Unknown
        }
        return QueuedUpdateResult.Failed(message = message, kind = kind)
    }
}

object QueuedUpdateRunner {
    suspend fun run(
        context: Context,
        payload: QueuedUpdatePayload,
        useInstallConstraints: Boolean,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): QueuedUpdateResult {
        ServiceLocator.init(context.applicationContext)
        val sl = ServiceLocator
        val info = payload.toAppInfo()
        if (!sl.installer.canRequestInstalls()) {
            val message = "Install unknown apps permission is not granted"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }

        val cached = sl.appIdCache.get(payload.sourceKey, payload.owner, payload.repo)
        val applicationId = payload.applicationId ?: cached?.applicationId
        val installedInfo = applicationId?.let { sl.installState.info(it) }
        if (applicationId == null || installedInfo == null) {
            val message = "Queued update skipped; ${payload.owner}/${payload.repo} is no longer installed"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }

        val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
        val target = File(cacheDir, "${payload.owner}_${payload.repo}_${payload.tagName}_queued.apk")
        return try {
            sl.logger.info("QueuedUpdate", "Downloading ${payload.owner}/${payload.repo} ${payload.tagName}")
            sl.github.download(
                url = payload.assetUrl,
                target = target,
                patOverride = sl.settings.getPat(payload.sourceKey),
                onProgress = onProgress,
            )

            val meta = when (val inspection = sl.apkInspector.inspectResult(target)) {
                is ApkInspectionResult.Verified -> inspection.metadata
                is ApkInspectionResult.Rejected -> {
                    val message = inspection.reason.userMessage
                    sl.logger.warn(
                        "QueuedUpdate",
                        "Rejected ${payload.owner}/${payload.repo} APK: " +
                            "${inspection.reason.name} (${inspection.diagnostics})",
                    )
                    return QueuedUpdateResult.Failed(
                        message,
                        if (inspection.reason.isSignatureFailure) {
                            QueuedUpdateFailureKind.Signature
                        } else {
                            QueuedUpdateFailureKind.InvalidArtifact
                        },
                    )
                }
            }
            val hydratedInfo = info.copy(applicationId = meta.applicationId)
            if (meta.applicationId != applicationId) {
                sl.audit.installBlocked(hydratedInfo, meta, "application_id_changed")
                return QueuedUpdateResult.Failed(
                    "Downloaded APK package changed from $applicationId to ${meta.applicationId}",
                    QueuedUpdateFailureKind.PackageIdentity,
                )
            }

            val pinned = sl.secrets.getPin(meta.applicationId)
            val lineageRotationAccepted = pinned != null && pinned != meta.signingSha256 && pinned in meta.lineageSha256
            val pinAccepted = pinned.isNullOrEmpty() || pinned == meta.signingSha256 || lineageRotationAccepted
            if (!pinAccepted) {
                sl.audit.installBlocked(hydratedInfo, meta, "signature_pin_mismatch")
                val message = "Publisher key changed for ${meta.applicationId}; queued update blocked"
                sl.logger.warn("QueuedUpdate", message)
                return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Signature)
            }

            sl.appIdCache.recordInspected(info, meta)
            if (meta.versionCode <= installedInfo.versionCode) {
                val relation = if (meta.versionCode == installedInfo.versionCode) {
                    "same-version release"
                } else {
                    "downgrade"
                }
                val message = "Queued update stopped: inspected APK is a $relation " +
                    "(${meta.versionCode} vs installed ${installedInfo.versionCode}); " +
                    "foreground confirmation is required"
                sl.audit.installBlocked(hydratedInfo, meta, "queued_non_upgrade")
                sl.logger.warn("QueuedUpdate", message)
                return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
            }

            val newDangerousPerms = PermissionDiff.newDangerousPermissions(sl.appContext, meta)
            if (newDangerousPerms.isNotEmpty()) {
                sl.audit.installBlocked(hydratedInfo, meta, "permission_review_required")
                val message = "Queued update requires permission review: ${newDangerousPerms.joinToString()}"
                sl.logger.warn("QueuedUpdate", message)
                return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.PermissionReview)
            }

            sl.developerVerification.evaluate(meta).let { notice ->
                sl.audit.developerVerificationWarned(hydratedInfo, meta, notice.reason)
                sl.logger.warn("QueuedUpdate", "Developer Verification advisory for ${meta.applicationId}: ${notice.reason}")
            }

            val referrerUri = Uri.parse(payload.assetUrl)
            val result = if (useInstallConstraints && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val resultData = QueuedInstallResultHandler.resultData(
                    payload.copy(applicationId = meta.applicationId),
                    QueuedInstallMetadata.from(meta, pinned, lineageRotationAccepted),
                )
                sl.installer.queueInstallAfterConstraints(
                    apk = target,
                    applicationId = meta.applicationId,
                    firstInstall = false,
                    referrerUri = referrerUri,
                    resultData = resultData,
                )
            } else {
                sl.installer.installApk(
                    apk = target,
                    applicationId = meta.applicationId,
                    firstInstall = false,
                    referrerUri = referrerUri,
                )
            }

            when (result) {
                InstallResult.Success -> {
                    recordImmediateSuccess(payload, meta, pinned, lineageRotationAccepted)
                    sl.audit.installSucceeded(hydratedInfo, meta)
                    sl.logger.info("QueuedUpdate", "Installed ${meta.applicationId} ${meta.versionName.orEmpty()}")
                    QueuedUpdateResult.Installed
                }
                is InstallResult.Queued -> {
                    sl.logger.info("QueuedUpdate", "Queued ${meta.applicationId} until app-not-foreground, device-idle, and not-in-call constraints are met")
                    QueuedUpdateResult.Queued(result.sessionId)
                }
                is InstallResult.Failure -> {
                    sl.audit.installFailed(hydratedInfo, meta, result.message)
                    QueuedUpdateFailureClassifier.fromInstaller(result.status, result.message)
                }
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            sl.logger.error("QueuedUpdate", "Queued update failed", t)
            QueuedUpdateFailureClassifier.fromThrowable(t)
        } finally {
            target.delete()
        }
    }

    private fun fail(
        payload: QueuedUpdatePayload,
        meta: com.sysadmin.lasstore.data.ApkMetadata?,
        message: String,
        kind: QueuedUpdateFailureKind,
    ): QueuedUpdateResult {
        val sl = ServiceLocator
        if (meta != null) {
            sl.audit.installFailed(payload.toAppInfo(), meta, message)
        }
        sl.logger.warn("QueuedUpdate", message)
        return QueuedUpdateResult.Failed(message, kind)
    }

    private fun recordImmediateSuccess(
        payload: QueuedUpdatePayload,
        meta: com.sysadmin.lasstore.data.ApkMetadata,
        pinned: String?,
        lineageRotationAccepted: Boolean,
    ) {
        val sl = ServiceLocator
        if (!meta.isEligibleForPinEnrollment) {
            sl.logger.error(
                "QueuedUpdate",
                "Installed ${meta.applicationId}, but refused unverified signer-pin enrollment",
            )
        } else if (pinned.isNullOrEmpty()) {
            sl.secrets.setPin(meta.applicationId, meta.signingSha256)
        } else if (pinned != meta.signingSha256 && lineageRotationAccepted) {
            sl.secrets.setPin(meta.applicationId, meta.signingSha256)
            sl.logger.info("QueuedUpdate", "Rolled pin forward for ${meta.applicationId}: $pinned -> ${meta.signingSha256}")
        }
        sl.appIdCache.recordInstalled(payload.toAppInfo(), meta)
    }
}
