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
                val verification = runCatching {
                    verifyInstallArtifact(
                        expectedApplicationId = applicationId,
                        installedInfo = installed,
                        metadata = metadata,
                        pinnedSignerSha256 = sl.secrets.getPin(applicationId),
                        declaredSignerSha256 = sl.libraryRestore.declaredSignerFor(applicationId),
                    )
                }
                if (verification.isFailure) {
                    sl.logger.error(
                        "QueuedUpdate",
                        "Could not verify reconciled install trust state",
                        verification.exceptionOrNull(),
                    )
                    sl.queuedUpdateStatus.markAuditPending(
                        payload,
                        status.attempt,
                        "Install completed, but durable audit evidence is pending.",
                    )
                    false
                } else if (verification.getOrThrow() is ArtifactVerificationResult.Rejected) {
                    val rejection = verification.getOrThrow() as ArtifactVerificationResult.Rejected
                    sl.logger.error(
                        "QueuedUpdate",
                        "Reconciled install trust state was rejected: ${rejection.message}",
                    )
                    sl.queuedUpdateStatus.markAuditPending(
                        payload,
                        status.attempt,
                        "Install completed, but durable trust state is pending.",
                    )
                    false
                } else {
                    val accepted = verification.getOrThrow() as ArtifactVerificationResult.Accepted
                    if (!InstallTrustStateFinalizer.finalizeSuccessfulInstall(
                            info = info,
                            metadata = metadata,
                            previousPinnedSignerSha256 = accepted.pinnedSignerSha256,
                            logger = sl.logger,
                        )
                    ) {
                        sl.queuedUpdateStatus.markAuditPending(
                            payload,
                            status.attempt,
                            "Install completed, but durable audit evidence is pending.",
                        )
                        false
                    } else {
                        sl.queuedUpdateStatus.markInstalled(
                            payload,
                            "Background update reconciled after process restart.",
                        )
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

        if (sl.installer.hasOpenSession(sessionId)) {
            if (status.phase != QueuedUpdatePhase.AwaitingUserAction) {
                sl.queuedUpdateStatus.markAwaitingInstall(payload, status.attempt, sessionId)
            }
            return QueuedInstallReconciliation.AwaitingSession(sessionId)
        }
        return QueuedInstallReconciliation.NotApplicable
    }
}
