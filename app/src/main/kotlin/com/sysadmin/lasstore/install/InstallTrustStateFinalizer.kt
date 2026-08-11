package com.sysadmin.lasstore.install

import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo

/**
 * Single durable success transition shared by foreground and queued installs.
 * The caller remains responsible for its operation/status lease; this service owns audit,
 * signer-pin enrollment/rotation, and installed-app cache ordering.
 */
internal object InstallTrustStateFinalizer {
    fun finalizeSuccessfulInstall(
        info: AppInfo,
        metadata: ApkMetadata,
        previousPinnedSignerSha256: String?,
        logger: Logger,
    ): Boolean {
        val sl = ServiceLocator
        if (!sl.audit.installSuccessPending(info, metadata)) {
            logger.error("Install", "Could not write install-success pending audit evidence")
            return false
        }
        val stateUpdate = runCatching {
            if (!metadata.isEligibleForPinEnrollment) {
                logger.error(
                    "Install",
                    "Installed ${metadata.applicationId}, but refused unverified signer-pin enrollment",
                )
            } else if (previousPinnedSignerSha256.isNullOrBlank()) {
                sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
                check(sl.secrets.getPin(metadata.applicationId) == metadata.signingSha256) {
                    "Signer pin enrollment did not persist"
                }
            } else if (
                previousPinnedSignerSha256 != metadata.signingSha256 &&
                previousPinnedSignerSha256 in metadata.lineageSha256
            ) {
                sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
                check(sl.secrets.getPin(metadata.applicationId) == metadata.signingSha256) {
                    "Signer pin rotation did not persist"
                }
                logger.info(
                    "Install",
                    "Rolled pin forward for ${metadata.applicationId}: " +
                        "$previousPinnedSignerSha256 -> ${metadata.signingSha256}",
                )
            }
            sl.appIdCache.recordInstalled(info, metadata)
            runCatching { sl.apkLockfile.recordInstalled(info, metadata) }
                .onFailure { failure ->
                    logger.warn(
                        "Install",
                        "Could not update APK lockfile for ${metadata.applicationId}: " +
                            (failure.message ?: "write failed"),
                    )
                }
        }
        if (stateUpdate.isFailure) {
            logger.error(
                "Install",
                "Could not commit installed trust/cache state",
                stateUpdate.exceptionOrNull(),
            )
            return false
        }
        if (!sl.audit.installSucceeded(info, metadata)) {
            logger.error("Install", "Install success audit completion is pending")
            return false
        }
        return true
    }
}
