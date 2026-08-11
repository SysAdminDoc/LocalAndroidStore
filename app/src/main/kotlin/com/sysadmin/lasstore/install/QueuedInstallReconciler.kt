package com.sysadmin.lasstore.install

import android.content.Context
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ServiceLocator

internal sealed interface QueuedInstallReconciliation {
    data object NotApplicable : QueuedInstallReconciliation
    data object Installed : QueuedInstallReconciliation
    data class AwaitingSession(val sessionId: Int) : QueuedInstallReconciliation
}

/**
 * Repairs a queued update when Android completed the PackageInstaller commit after the process
 * that launched it died. The persisted operation record is the source of truth; a matching
 * installed package is finalized before the worker considers downloading again.
 */
internal object QueuedInstallReconciler {
    fun reconcile(
        context: Context,
        payload: QueuedUpdatePayload,
    ): QueuedInstallReconciliation {
        ServiceLocator.init(context.applicationContext)
        val sl = ServiceLocator
        val status = sl.queuedUpdateStatus.get(payload) ?: return QueuedInstallReconciliation.NotApplicable
        if (!sl.queuedUpdateStatus.isCurrent(payload)) return QueuedInstallReconciliation.NotApplicable

        val sessionId = status.packageInstallerSessionId
            ?: return QueuedInstallReconciliation.NotApplicable
        val applicationId = status.targetApplicationId
            ?: payload.applicationId
            ?: sl.appIdCache.get(payload.sourceKey, payload.owner, payload.repo)?.applicationId
            ?: return QueuedInstallReconciliation.NotApplicable
        val targetVersionCode = status.targetVersionCode
            ?: return QueuedInstallReconciliation.NotApplicable
        val targetSignerSha256 = status.targetSignerSha256
            ?: return QueuedInstallReconciliation.NotApplicable
        val installed = sl.installState.info(applicationId)

        if (
            installed?.versionCode == targetVersionCode &&
            installed.currentSignerSha256 == targetSignerSha256
        ) {
            val metadata = ApkMetadata(
                applicationId = applicationId,
                versionName = status.targetVersionName,
                versionCode = targetVersionCode,
                label = payload.displayName,
                signingSha256 = targetSignerSha256,
                lineageSha256 = status.targetLineageSha256,
                verifiedSignatureSchemes = status.targetVerifiedSignatureSchemes,
            )
            val info = payload.toAppInfo().copy(applicationId = applicationId)
            val finalized = sl.queuedUpdateStatus.ifCurrent(payload) {
                if (!sl.audit.installSuccessPending(info, metadata)) {
                    sl.queuedUpdateStatus.markAuditPending(
                        payload,
                        status.attempt,
                        "Install completed, but durable audit evidence is pending.",
                    )
                    false
                } else {
                    val stateUpdate = runCatching {
                        val pinned = sl.secrets.getPin(applicationId)
                        check(pinned.isNullOrBlank() || pinned == targetSignerSha256) {
                            "Installed signer does not match the stored publisher pin"
                        }
                        if (pinned.isNullOrBlank() && metadata.isEligibleForPinEnrollment) {
                            sl.secrets.setPin(applicationId, targetSignerSha256)
                            check(sl.secrets.getPin(applicationId) == targetSignerSha256) {
                                "Signer pin enrollment did not persist"
                            }
                        }
                        sl.appIdCache.recordInstalled(info, metadata)
                        check(sl.audit.installSucceeded(info, metadata)) {
                            "Install success audit completion is pending"
                        }
                        sl.queuedUpdateStatus.markInstalled(
                            payload,
                            "Background update reconciled after process restart.",
                        )
                    }
                    if (stateUpdate.isFailure) {
                        sl.logger.error(
                            "QueuedUpdate",
                            "Could not reconcile installed queued update",
                            stateUpdate.exceptionOrNull(),
                        )
                        sl.queuedUpdateStatus.markAuditPending(
                            payload,
                            status.attempt,
                            "Install completed, but durable audit evidence is pending.",
                        )
                        false
                    } else {
                        true
                    }
                }
            } ?: false
            if (finalized) {
                sl.installer.abandonSession(sessionId)
                sl.logger.info(
                    "QueuedUpdate",
                    "Reconciled ${applicationId} ${targetVersionCode} after process restart",
                )
                return QueuedInstallReconciliation.Installed
            }
            return QueuedInstallReconciliation.NotApplicable
        }

        val session = context.packageManager.packageInstaller.mySessions
            .firstOrNull { it.sessionId == sessionId }
        if (session != null) {
            sl.queuedUpdateStatus.markAwaitingInstall(payload, status.attempt, sessionId)
            return QueuedInstallReconciliation.AwaitingSession(sessionId)
        }
        return QueuedInstallReconciliation.NotApplicable
    }
}
