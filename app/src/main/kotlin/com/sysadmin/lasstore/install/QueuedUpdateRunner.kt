package com.sysadmin.lasstore.install

import android.content.Context
import android.net.Uri
import android.os.Build
import android.content.pm.PackageInstaller
import com.sysadmin.lasstore.data.ApkInspectionResult
import com.sysadmin.lasstore.data.GitHubFailureKind
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.InstallArtifactKind
import com.sysadmin.lasstore.data.InstallProvenance
import com.sysadmin.lasstore.data.InvalidReleaseAssetDigestException
import com.sysadmin.lasstore.data.ReleaseAssetDigestMismatchException
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.normalizeSha256Digest
import com.sysadmin.lasstore.data.installArtifactKind
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface QueuedUpdateResult {
    data object Installed : QueuedUpdateResult
    data object Stale : QueuedUpdateResult
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
        is ReleaseAssetDigestMismatchException,
        is InvalidReleaseAssetDigestException ->
            QueuedUpdateResult.Failed(
                throwable.message ?: "Release asset integrity verification failed.",
                QueuedUpdateFailureKind.InvalidArtifact,
            )
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
        attempt: Int = 0,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): QueuedUpdateResult {
        ServiceLocator.init(context.applicationContext)
        val sl = ServiceLocator
        val info = payload.toAppInfo()
        if (installArtifactKind(info.asset.name) != InstallArtifactKind.APK) {
            val message = "APK-set updates require foreground split selection."
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }
        if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
        if (normalizeSha256Digest(payload.assetDigest) == null) {
            val message = "Queued update blocked; GitHub did not publish a valid SHA-256 digest " +
                "for this release asset"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.InvalidArtifact)
        }
        if (!sl.installer.canRequestInstalls()) {
            val message = "Install unknown apps permission is not granted"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }

        val cached = sl.appIdCache.get(payload.sourceKey, payload.owner, payload.repo)
        if (cached == null || cached.provenance == InstallProvenance.EXTERNAL_UNMANAGED) {
            val message = "Queued update blocked; the installed app has not been adopted by LocalAndroidStore"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }
        val applicationId = payload.applicationId ?: cached.applicationId
        val installedInfo = sl.installState.info(applicationId)
        if (installedInfo == null) {
            val message = "Queued update skipped; ${payload.owner}/${payload.repo} is no longer installed"
            sl.logger.warn("QueuedUpdate", message)
            return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
        }
        if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale

        val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
        val target = File(cacheDir, "${payload.owner}_${payload.repo}_${payload.tagName}_queued.apk")
        return try {
            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
            sl.logger.info("QueuedUpdate", "Downloading ${payload.owner}/${payload.repo} ${payload.tagName}")
            sl.github.download(
                url = payload.assetUrl,
                target = target,
                patOverride = sl.settings.getPat(payload.sourceKey),
                expectedDigest = payload.assetDigest,
                onProgress = onProgress,
            )
            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale

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
            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
            val hydratedInfo = info.copy(applicationId = meta.applicationId)
            val verification = verifyInstallArtifact(
                expectedApplicationId = applicationId,
                installedInfo = installedInfo,
                metadata = meta,
                pinnedSignerSha256 = sl.secrets.getPin(meta.applicationId),
            )
            if (verification is ArtifactVerificationResult.Rejected) {
                if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
                val reason = when (verification.reason) {
                    ArtifactVerificationRejection.PackageIdentity -> "application_id_changed"
                    ArtifactVerificationRejection.InstalledSigner -> "installed_signer_mismatch"
                    ArtifactVerificationRejection.PublisherPin -> "signature_pin_mismatch"
                }
                sl.audit.installBlocked(hydratedInfo, meta, reason)
                sl.logger.warn("QueuedUpdate", verification.message)
                return QueuedUpdateResult.Failed(
                    verification.message,
                    when (verification.reason) {
                        ArtifactVerificationRejection.PackageIdentity -> QueuedUpdateFailureKind.PackageIdentity
                        ArtifactVerificationRejection.InstalledSigner,
                        ArtifactVerificationRejection.PublisherPin -> QueuedUpdateFailureKind.Signature
                    },
                )
            }
            val accepted = verification as ArtifactVerificationResult.Accepted
            val pinned = accepted.pinnedSignerSha256
            val lineageRotationAccepted = accepted.lineageRotationAccepted
            if (lineageRotationAccepted) {
                sl.logger.info(
                    "QueuedUpdate",
                    "Pinned cert $pinned appears in v3 lineage of ${meta.applicationId}; " +
                        "accepting legitimate key rotation to ${meta.signingSha256}",
                )
            }

            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
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
                if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
                sl.audit.installBlocked(hydratedInfo, meta, "queued_non_upgrade")
                sl.logger.warn("QueuedUpdate", message)
                return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.Policy)
            }

            val newDangerousPerms = PermissionDiff.newDangerousPermissions(sl.appContext, meta)
            if (newDangerousPerms.isNotEmpty()) {
                if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
                sl.audit.installBlocked(hydratedInfo, meta, "permission_review_required")
                val message = "Queued update requires permission review: ${newDangerousPerms.joinToString()}"
                sl.logger.warn("QueuedUpdate", message)
                return QueuedUpdateResult.Failed(message, QueuedUpdateFailureKind.PermissionReview)
            }

            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
            sl.developerVerification.evaluate(meta).let { notice ->
                sl.audit.developerVerificationWarned(hydratedInfo, meta, notice.reason)
                sl.logger.warn("QueuedUpdate", "Developer Verification advisory for ${meta.applicationId}: ${notice.reason}")
            }

            val referrerUri = Uri.parse(payload.assetUrl)
            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
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
                    operationId = payload.generationId,
                    onSessionCreated = { sessionId ->
                        check(
                            sl.queuedUpdateStatus.markInstallerSession(
                                payload,
                                attempt,
                                sessionId,
                                metadata = meta,
                            ),
                        ) { "Queued update was replaced before install session creation" }
                    },
                )
            } else {
                sl.installer.queueInstallWithoutConstraints(
                    apk = target,
                    applicationId = meta.applicationId,
                    firstInstall = false,
                    referrerUri = referrerUri,
                    resultData = QueuedInstallResultHandler.resultData(
                        payload.copy(applicationId = meta.applicationId),
                        QueuedInstallMetadata.from(meta, pinned, lineageRotationAccepted),
                    ),
                    operationId = payload.generationId,
                    onSessionCreated = { sessionId ->
                        check(
                            sl.queuedUpdateStatus.markInstallerSession(
                                payload,
                                attempt,
                                sessionId,
                                metadata = meta,
                            ),
                        ) { "Queued update was replaced before install session creation" }
                    },
                )
            }

            if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
            when (result) {
                InstallResult.Success -> {
                    if (recordImmediateSuccess(
                            payload = payload,
                            info = hydratedInfo,
                            meta = meta,
                            pinned = pinned,
                        )
                    ) {
                        sl.logger.info("QueuedUpdate", "Installed ${meta.applicationId} ${meta.versionName.orEmpty()}")
                        QueuedUpdateResult.Installed
                    } else {
                        QueuedUpdateResult.Failed(
                            message = "Install completed, but durable audit evidence is pending.",
                            kind = QueuedUpdateFailureKind.AuditPending,
                        )
                    }
                }
                is InstallResult.Queued -> {
                    sl.logger.info("QueuedUpdate", "Queued ${meta.applicationId} until app-not-foreground, device-idle, and not-in-call constraints are met")
                    QueuedUpdateResult.Queued(result.sessionId)
                }
                is InstallResult.Failure -> {
                    if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedUpdateResult.Stale
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
        info: com.sysadmin.lasstore.domain.AppInfo,
        meta: com.sysadmin.lasstore.data.ApkMetadata,
        pinned: String?,
    ): Boolean {
        val sl = ServiceLocator
        return sl.queuedUpdateStatus.ifCurrent(payload) {
            InstallTrustStateFinalizer.finalizeSuccessfulInstall(
                info = info,
                metadata = meta,
                previousPinnedSignerSha256 = pinned,
                logger = sl.logger,
            ).also { finalized ->
                if (finalized) {
                    sl.queuedUpdateStatus.markInstalled(payload)
                } else {
                    sl.logger.error("QueuedUpdate", "Install success audit completion is pending")
                }
            }
        } ?: false
    }
}
