package com.sysadmin.lasstore.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.ServiceLocator

internal object QueuedInstallResultHandler {
    fun handle(context: Context, intent: Intent, logger: Logger) {
        ServiceLocator.init(context.applicationContext)
        val sl = ServiceLocator
        val payload = QueuedUpdatePayload.from(intent)
        val metadata = QueuedInstallMetadata.from(intent)
        if (payload == null || metadata == null) {
            logger.warn("QueuedUpdate", "Install result missing queued update metadata")
            return
        }
        if (!sl.queuedUpdateStatus.isCurrent(payload)) {
            abandonStaleSession(sl, intent)
            logger.info(
                "QueuedUpdate",
                "Ignoring stale install result for ${payload.owner}/${payload.repo} " +
                    "generation ${payload.generationId}",
            )
            return
        }
        if (sl.queuedUpdateStatus.isFinalized(payload)) {
            abandonStaleSession(sl, intent)
            logger.info(
                "QueuedUpdate",
                "Ignoring duplicate install result for finalized ${payload.owner}/${payload.repo}",
            )
            return
        }

        val info = payload.toAppInfo().copy(applicationId = metadata.applicationId)
        val meta = metadata.toApkMetadata()
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.pendingUserActionIntent()
                val sessionId = intent.getIntExtra(
                    PackageInstaller.EXTRA_SESSION_ID,
                    intent.getIntExtra(EXTRA_DECLARED_SESSION_ID, -1),
                )
                val attempt = sl.queuedUpdateStatus.get(payload)?.attempt ?: 1
                if (confirm != null && sessionId >= 0) {
                    if (sl.queuedUpdateStatus.markAwaitingUserAction(payload, attempt, sessionId)) {
                        QueuedUpdateUserActionNotification.show(context, payload, confirm)
                    }
                } else {
                    val failure = QueuedUpdateResult.Failed(
                        "Android requested user confirmation, but no confirmation action was available. Retry from the catalog.",
                        QueuedUpdateFailureKind.Policy,
                    )
                    if (sl.queuedUpdateStatus.markFailed(payload, attempt, failure)) {
                        sl.audit.installFailed(info, meta, failure.message)
                    }
                    if (sessionId >= 0) sl.installer.abandonSession(sessionId)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                if (recordSuccess(payload, info, metadata, meta)) {
                    if (!sl.queuedUpdateStatus.isCurrent(payload)) return
                    sl.queuedUpdateStatus.markInstalled(payload)
                    QueuedUpdateUserActionNotification.cancel(context, payload)
                    sl.logger.info("QueuedUpdate", "Installed ${metadata.applicationId} ${metadata.versionName.orEmpty()} after constraints")
                } else {
                    if (!sl.queuedUpdateStatus.isCurrent(payload)) return
                    sl.queuedUpdateStatus.markAuditPending(
                        payload,
                        sl.queuedUpdateStatus.get(payload)?.attempt ?: 1,
                        "Install completed, but durable audit evidence is pending.",
                    )
                    sl.logger.error("QueuedUpdate", "Install completed but audit evidence is pending for ${metadata.applicationId}")
                }
            }
            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE,
            PackageInstaller.STATUS_FAILURE_TIMEOUT -> {
                val decoded = decodeFailure(context, status, message)
                if (!sl.queuedUpdateStatus.isCurrent(payload)) return
                if (sl.queuedUpdateStatus.markFailed(
                    payload,
                    sl.queuedUpdateStatus.get(payload)?.attempt ?: 1,
                    QueuedUpdateFailureClassifier.fromInstaller(status, decoded),
                )) {
                    sl.audit.installFailed(info, meta, decoded)
                    QueuedUpdateUserActionNotification.cancel(context, payload)
                    sl.logger.warn("QueuedUpdate", "Install failed for ${metadata.applicationId}: $decoded")
                }
            }
            else -> sl.logger.warn("QueuedUpdate", "Unknown PackageInstaller status $status for ${metadata.applicationId}")
        }
    }

    private fun recordSuccess(
        payload: QueuedUpdatePayload,
        info: com.sysadmin.lasstore.domain.AppInfo,
        metadata: QueuedInstallMetadata,
        apkMetadata: com.sysadmin.lasstore.data.ApkMetadata,
    ): Boolean {
        val sl = ServiceLocator
        return sl.queuedUpdateStatus.ifCurrent(payload) {
            if (!sl.audit.installSuccessPending(info, apkMetadata)) {
                sl.logger.error("QueuedUpdate", "Could not write install-success pending audit evidence")
                return@ifCurrent false
            }
            val previousPin = metadata.previousPinnedSha256
            val stateUpdate = runCatching {
                if (!apkMetadata.isEligibleForPinEnrollment) {
                    sl.logger.error(
                        "QueuedUpdate",
                        "Installed ${metadata.applicationId}, but refused unverified signer-pin enrollment",
                    )
                } else if (previousPin.isNullOrBlank()) {
                    sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
                    check(sl.secrets.getPin(metadata.applicationId) == metadata.signingSha256) {
                        "Signer pin enrollment did not persist"
                    }
                } else if (previousPin != metadata.signingSha256 && metadata.lineageRotationAccepted) {
                    sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
                    check(sl.secrets.getPin(metadata.applicationId) == metadata.signingSha256) {
                        "Signer pin rotation did not persist"
                    }
                    sl.logger.info("QueuedUpdate", "Rolled pin forward for ${metadata.applicationId}: $previousPin -> ${metadata.signingSha256}")
                }
                sl.appIdCache.recordInstalled(payload.toAppInfo(), apkMetadata)
            }
            if (stateUpdate.isFailure) {
                sl.logger.error("QueuedUpdate", "Could not commit installed trust/cache state", stateUpdate.exceptionOrNull())
                return@ifCurrent false
            }
            sl.audit.installSucceeded(info, apkMetadata).also { written ->
                if (written) {
                    sl.queuedUpdateStatus.markInstalled(payload)
                } else {
                    sl.logger.error("QueuedUpdate", "Install success audit completion is pending")
                }
            }
        } ?: false
    }

    private fun abandonStaleSession(sl: com.sysadmin.lasstore.data.ServiceLocator, intent: Intent) {
        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            intent.getIntExtra(EXTRA_DECLARED_SESSION_ID, -1),
        )
        if (sessionId >= 0) sl.installer.abandonSession(sessionId)
    }

    fun resultData(payload: QueuedUpdatePayload, metadata: QueuedInstallMetadata): Intent =
        metadata.putInto(payload.putInto(Intent()))
}
