package com.sysadmin.lasstore.install

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.InstallProvenance
import com.sysadmin.lasstore.data.InstalledInfo
import com.sysadmin.lasstore.data.ReleaseAssetIdentity
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.UpdateCadenceMode
import com.sysadmin.lasstore.data.normalizeSha256Digest
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CatalogFailureKind
import com.sysadmin.lasstore.domain.DiscoveryUseCase
import com.sysadmin.lasstore.domain.aggregateCatalogApps
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Refreshes configured sources and queues safe, already-managed release changes.
 *
 * This worker never installs an unknown package. The existing [QueuedUpdateRunner] remains the
 * final authority for digest, APK identity, signer, version, permission, and installer checks.
 */
class PeriodicUpdateCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        ServiceLocator.init(applicationContext)
        val sl = ServiceLocator
        return try {
            val settings = sl.settings.flow.first()
            if (settings.sources.none { it.enabled } && settings.fdroidSources.none { it.enabled }) {
                sl.logger.info("PeriodicUpdate", "Skipped check because no sources are enabled")
                return Result.success()
            }

            val discovery = DiscoveryUseCase(
                github = sl.github,
                logger = sl.logger,
                snapshots = sl.catalogSnapshots,
                patForSource = { sourceKey -> sl.settings.getPat(sourceKey) },
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                fdroidIndexProvider = sl.fdroidIndex,
                preferredChannelFor = { source, owner, repo ->
                    sl.channelPreferences.get(source.key, owner, repo)
                },
            )
            val discovered = discovery.discover(settings.sources, settings.fdroidSources)
            discovered.issues.forEach { issue ->
                sl.logger.warn(
                    "PeriodicUpdate",
                    "${issue.sourceLabel}: ${issue.message}",
                )
            }

            val candidates = discovered.apps
                .map { info ->
                    sl.appIdCache.get(info.sourceKey, info.owner, info.repo)
                        ?.applicationId
                        ?.let { applicationId -> info.copy(applicationId = applicationId) }
                        ?: info
                }
                .let { infos ->
                    aggregateCatalogApps(
                        infos = infos,
                        preferredSourceFor = sl.preferredSources::get,
                        candidateAllowed = { info ->
                            info.minSdk == null || info.minSdk <= Build.VERSION.SDK_INT
                        },
                    ).map { it.primary }
                }

            var queuedCount = 0
            var availableCount = 0
            candidates.forEach { info ->
                val cached = sl.appIdCache.get(info.sourceKey, info.owner, info.repo)
                    ?: return@forEach
                val installed = sl.installState.info(cached.applicationId)
                    ?: return@forEach
                val reconciled = sl.appIdCache.reconcileInstalled(cached, installed)
                if (!shouldQueuePeriodicUpdate(
                        info = info.copy(applicationId = reconciled.applicationId),
                        cached = reconciled,
                        installed = installed,
                        ignored = sl.ignoreList.isIgnored(info.handle),
                        queued = sl.queuedUpdateStatus.get(info.sourceKey, info.owner, info.repo),
                        pinnedSignerSha256 = sl.secrets.getPin(reconciled.applicationId),
                    )
                ) {
                    return@forEach
                }
                val cadence = sl.updateCadences.get(info.copy(applicationId = reconciled.applicationId))
                if (cadence.isHeld()) return@forEach
                if (cadence.mode == UpdateCadenceMode.Notify) {
                    availableCount += 1
                    return@forEach
                }
                val reserved = cadence.mode == UpdateCadenceMode.Pinned ||
                    sl.updateCadences.tryReserveDailySlot(settings.dailyUpdateCap)
                if (!reserved) {
                    availableCount += 1
                    return@forEach
                }
                val queued = sl.backgroundUpdates.enqueuePeriodic(
                    info.copy(applicationId = reconciled.applicationId),
                )
                if (queued) {
                    queuedCount += 1
                } else if (cadence.mode != UpdateCadenceMode.Pinned) {
                    sl.updateCadences.releaseDailySlot()
                }
            }

            if (queuedCount > 0 || availableCount > 0) {
                PeriodicUpdateNotification.show(applicationContext, queuedCount, availableCount)
                sl.logger.info(
                    "PeriodicUpdate",
                    "Queued $queuedCount catalog update(s); $availableCount available for review",
                )
            }

            val transientSourceFailure = discovered.apps.isEmpty() && discovered.issues.any {
                it.kind == CatalogFailureKind.Network ||
                    it.kind == CatalogFailureKind.Tls ||
                    it.kind == CatalogFailureKind.Server ||
                    it.kind == CatalogFailureKind.RateLimited
            }
            if (transientSourceFailure) Result.retry() else Result.success()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            sl.logger.error("PeriodicUpdate", "Periodic catalog check failed", throwable)
            Result.retry()
        }
    }
}

/** Pure eligibility boundary used by the worker and deterministic tests. */
internal fun shouldQueuePeriodicUpdate(
    info: AppInfo,
    cached: AppIdEntry?,
    installed: InstalledInfo?,
    ignored: Boolean,
    queued: QueuedUpdateStatus?,
    pinnedSignerSha256: String? = null,
): Boolean {
    if (cached == null || installed == null) return false
    if (cached.provenance == InstallProvenance.EXTERNAL_UNMANAGED) return false
    if (cached.applicationId != installed.applicationId) return false
    if (info.applicationId != null && !info.applicationId.equals(cached.applicationId, ignoreCase = true)) {
        return false
    }
    if (ignored || info.isStale || info.assetChoices.size > 1) return false
    if (normalizeSha256Digest(info.asset.digest) == null) return false
    if (
        pinnedSignerSha256 != null &&
        installed.currentSignerSha256 != pinnedSignerSha256
    ) return false

    val installedAsset = cached.installedAsset ?: return false
    if (ReleaseAssetIdentity.from(info) == installedAsset) return false

    if (queued?.isPending == true) return false
    if (queued != null && queued.queuedPayload == null) return false
    val priorPayload = queued?.queuedPayload
    if (
        priorPayload?.assetId == info.asset.id &&
        priorPayload.assetDigest == info.asset.digest
    ) {
        return false
    }
    return true
}
