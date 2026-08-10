package com.sysadmin.lasstore.ui.catalog

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.ApkInspectionResult
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.DeveloperVerificationNotice
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.signerMatchesPin
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.domain.CatalogDiscoveryResult
import com.sysadmin.lasstore.domain.CatalogFailureKind
import com.sysadmin.lasstore.domain.CatalogSourceIssue
import com.sysadmin.lasstore.domain.DiscoveryUseCase
import com.sysadmin.lasstore.domain.ReleaseVersionRelation
import com.sysadmin.lasstore.domain.classifyReleaseVersion
import com.sysadmin.lasstore.install.InstallResult
import com.sysadmin.lasstore.install.ForegroundInstallFinalizer
import com.sysadmin.lasstore.install.ForegroundInstallPhase
import com.sysadmin.lasstore.install.PermissionDiff
import com.sysadmin.lasstore.install.PreapprovalSessionResult
import com.sysadmin.lasstore.install.QueuedUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CardState(
    val info: AppInfo,
    val status: CardStatus,
    val installedVersion: String? = null,
    val installedVersionCode: Long? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val developerVerificationNotice: DeveloperVerificationNotice? = null,
    /** New dangerous permissions the update requests vs the installed version (Item 34). */
    val newDangerousPermissions: List<String> = emptyList(),
    /** True when the user has silenced update notifications for this app (Item 35). */
    val isIgnored: Boolean = false,
    val queuedUpdateStatus: QueuedUpdateStatus? = null,
    val publisherTrustDetails: PublisherTrustDetails? = null,
)

data class PublisherTrustDetails(
    val source: String,
    val installedSignerSha256: String?,
    val storedPinSha256: String,
    val downloadedMetadata: ApkMetadata,
)

internal fun canAdvancePublisherPinRecovery(
    expectedApplicationId: String,
    typedApplicationId: String,
): Boolean = typedApplicationId.trim() == expectedApplicationId

internal fun canReplacePublisherPin(
    details: PublisherTrustDetails,
    typedApplicationId: String,
    independentlyVerified: Boolean,
): Boolean =
    independentlyVerified &&
        canAdvancePublisherPinRecovery(
            details.downloadedMetadata.applicationId,
            typedApplicationId,
        ) &&
        details.downloadedMetadata.isEligibleForPinEnrollment &&
        details.storedPinSha256 != details.downloadedMetadata.signingSha256

data class CatalogUiState(
    val refreshing: Boolean = false,
    val cards: List<CardState> = emptyList(),
    val searchQuery: String = "",
    val canRequestInstalls: Boolean = true,
    val errorMessage: String? = null,
    val catalogNotice: String? = null,
    val warning: String? = null,
)

class CatalogViewModel : ViewModel() {
    private val sl = ServiceLocator
    private val discovery = DiscoveryUseCase(
        github = sl.github,
        logger = sl.logger,
        snapshots = sl.catalogSnapshots,
        patForSource = { sourceKey -> sl.settings.getPat(sourceKey) },
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
    )

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    /** Active install jobs keyed by sourceKey/owner/repo. Used for cancellation. */
    private val activeJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var refreshJob: Job? = null
    @Volatile private var refreshGeneration = 0L

    /** APK + metadata held after inspection when waiting for permission review (Item 34). */
    private data class PendingInstallData(
        val apkFile: File,
        val meta: ApkMetadata,
        val pinned: String?,
        val installedAlready: Boolean,
        val preapprovalSessionId: Int?,
        val referrerUri: android.net.Uri,
    )
    private val pendingInstalls = ConcurrentHashMap<String, PendingInstallData>()

    init {
        refreshInstallPermission()
        recoverForegroundOperations()?.let { recoveryMessage ->
            _state.update { it.copy(warning = recoveryMessage) }
        }
        viewModelScope.launch {
            sl.queuedUpdateStatus.statuses.collectLatest {
                _state.update { ui ->
                    ui.copy(cards = ui.cards.map(::withQueuedUpdateStatus))
                }
            }
        }
        viewModelScope.launch {
            sl.foregroundInstalls.operations.collectLatest {
                _state.update { ui ->
                    ui.copy(
                        cards = ui.cards.map { card ->
                            val key = cardKey(card.info)
                            when {
                                sl.foregroundInstalls.get(key) != null ->
                                    withForegroundInstallState(card)
                                card.status == CardStatus.Working &&
                                    activeJobs[key] == null -> {
                                    val cached = sl.appIdCache.get(
                                        card.info.sourceKey,
                                        card.info.owner,
                                        card.info.repo,
                                    )
                                    buildCardState(card.info, cached)
                                }
                                else -> card
                            }
                        },
                    )
                }
            }
        }
        refresh()
    }

    private fun recoverForegroundOperations(): String? {
        var safelyTerminated = sl.foregroundInstalls.cleanupPendingMediaStoreRows()
        safelyTerminated += sl.foregroundInstalls.cleanupOrphanedApkFiles()
        sl.foregroundInstalls.operations.value.toList().forEach { operation ->
            when (operation.phase) {
                ForegroundInstallPhase.PermissionReview -> {
                    val apk = sl.foregroundInstalls.apkFile(operation)
                    val metadata = operation.metadata
                    if (apk != null && apk.isFile && metadata != null) {
                        pendingInstalls[operation.key] = PendingInstallData(
                            apkFile = apk,
                            meta = metadata,
                            pinned = operation.pinnedSignerSha256,
                            installedAlready = operation.installedAlready,
                            preapprovalSessionId = operation.preapprovalSessionId,
                            referrerUri = android.net.Uri.parse(operation.referrerUrl),
                        )
                    } else {
                        operation.preapprovalSessionId?.let(sl.installer::abandonSession)
                        sl.foregroundInstalls.remove(operation.key)
                        safelyTerminated += 1
                    }
                }
                ForegroundInstallPhase.Committing -> {
                    val sessionId = operation.installerSessionId
                    when {
                        sessionId != null && sl.installer.hasOpenSession(sessionId) -> Unit
                        ForegroundInstallFinalizer.reconcileCompletedOperation(
                            operation,
                            sl.logger,
                        ) -> Unit
                        else -> {
                            operation.metadata?.let { metadata ->
                                sl.audit.installFailed(
                                    operation.info,
                                    metadata,
                                    "Interrupted installer session no longer exists",
                                )
                            }
                            sl.foregroundInstalls.remove(operation.key)
                            safelyTerminated += 1
                        }
                    }
                }
                ForegroundInstallPhase.Preapproving,
                ForegroundInstallPhase.Downloading -> {
                    operation.preapprovalSessionId?.let(sl.installer::abandonSession)
                    operation.installerSessionId?.let(sl.installer::abandonSession)
                    sl.foregroundInstalls.remove(operation.key)
                    safelyTerminated += 1
                }
            }
        }
        return safelyTerminated.takeIf { it > 0 }?.let { count ->
            "Recovered startup state and safely cleaned $count interrupted " +
                "operation${if (count == 1) "" else "s"}."
        }
    }

    fun refreshInstallPermission() {
        _state.update { it.copy(canRequestInstalls = sl.installer.canRequestInstalls()) }
    }

    fun openInstallPermissionSettings() = sl.installer.openInstallPermissionSettings()

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        val job = viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true, errorMessage = null) }
            try {
                val settings = sl.settings.flow.first()
                ensureActive()
                val enabledSources = settings.sources.filter { it.enabled }
                val discoveryResult = try {
                    discovery.discover(settings.sources)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    sl.logger.error("Catalog", "discover failed", throwable)
                    CatalogDiscoveryResult(
                        apps = emptyList(),
                        issues = listOf(
                            CatalogSourceIssue(
                                sourceKey = "catalog",
                                sourceLabel = "Catalog",
                                kind = CatalogFailureKind.Unknown,
                                message = "Catalog refresh failed unexpectedly.",
                            )
                        ),
                    )
                }
                ensureActive()
                val infos = discoveryResult.apps
                sl.logger.info(
                    "Catalog",
                    "Discovered ${infos.size} APK-bearing repos across ${enabledSources.size} enabled sources"
                )
                // Hydrate applicationId from the persistent cache so UpdateAvailable survives cold starts.
                val cards = buildList {
                    infos.forEach { info ->
                        ensureActive()
                        val cached = sl.appIdCache.get(info.sourceKey, info.owner, info.repo)
                        add(buildCardState(info, cached))
                    }
                }
                ensureActive()
                val catalogNotice = catalogNotice(discoveryResult)
                _state.update { current ->
                    if (generation != refreshGeneration) {
                        current
                    } else {
                        current.copy(
                            refreshing = false,
                            cards = cards,
                            errorMessage = catalogNotice.takeIf {
                                cards.isEmpty() && discoveryResult.issues.isNotEmpty()
                            },
                            catalogNotice = catalogNotice.takeIf { cards.isNotEmpty() },
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (generation != refreshGeneration) return@launch
                sl.logger.error("Catalog", "refresh failed", throwable)
                _state.update {
                    it.copy(
                        refreshing = false,
                        errorMessage = "Catalog refresh failed unexpectedly.",
                    )
                }
            }
        }
        refreshJob = job
        job.invokeOnCompletion {
            if (refreshGeneration == generation) refreshJob = null
        }
    }

    /** Derive card state from package metadata and an inspected release, never from a tag. */
    private fun buildCardState(info: AppInfo, cached: AppIdEntry? = null): CardState {
        val applicationId = info.applicationId ?: cached?.applicationId
        val installed = applicationId?.let { sl.installState.info(it) }
        val isIgnored = sl.ignoreList.isIgnored(info.handle)
        val baseState = when {
            installed == null -> CardState(info = info, status = CardStatus.NotInstalled)
            else -> {
                val reconciled = cached?.let {
                    sl.appIdCache.reconcileInstalled(
                        entry = it,
                        installed = installed,
                    )
                }
                val pinnedSignerSha256 = sl.secrets.getPin(installed.applicationId)
                if (!signerMatchesPin(installed.currentSignerSha256, pinnedSignerSha256)) {
                    CardState(
                        info = info.copy(applicationId = applicationId),
                        status = CardStatus.SignatureMismatch,
                        installedVersion = installed.versionName,
                        installedVersionCode = installed.versionCode,
                        isIgnored = isIgnored,
                        message = "Installed publisher key does not match LocalAndroidStore's " +
                            "trust pin. Review the installed signer before updating.",
                    )
                } else {
                    val inspected = reconciled?.inspectedRelease
                        ?.takeIf { it.asset == com.sysadmin.lasstore.data.ReleaseAssetIdentity.from(info) }
                    val hydratedInfo = info.copy(
                        applicationId = applicationId,
                        versionCode = inspected?.versionCode,
                        versionName = inspected?.versionName ?: info.versionName,
                    )
                    val relation = reconciled?.let {
                        classifyReleaseVersion(hydratedInfo, it, installed.versionCode)
                    } ?: ReleaseVersionRelation.UninspectedRelease
                    val status = when {
                        relation == ReleaseVersionRelation.InstalledAsset -> CardStatus.Installed
                        relation == ReleaseVersionRelation.Upgrade && isIgnored -> CardStatus.Installed
                        relation == ReleaseVersionRelation.Upgrade -> CardStatus.UpdateAvailable
                        relation == ReleaseVersionRelation.SameVersionRelease ->
                            CardStatus.ReinstallAvailable
                        relation == ReleaseVersionRelation.Downgrade ->
                            CardStatus.DowngradeAvailable
                        relation == ReleaseVersionRelation.PackageMismatch -> CardStatus.Error
                        else -> CardStatus.ReleaseAvailable
                    }
                    CardState(
                        info = hydratedInfo,
                        status = status,
                        installedVersion = installed.versionName,
                        installedVersionCode = installed.versionCode,
                        isIgnored = isIgnored,
                        message = if (relation == ReleaseVersionRelation.PackageMismatch) {
                            "Release package ${inspected?.applicationId} does not match $applicationId."
                        } else {
                            null
                        },
                    )
                }
            }
        }
        return withForegroundInstallState(withQueuedUpdateStatus(baseState))
    }

    fun install(card: CardState) {
        val cachedApplicationId = card.info.applicationId ?: sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )?.applicationId
        val liveInstalled = cachedApplicationId?.let(sl.installState::info)
        if (
            liveInstalled != null &&
            !signerMatchesPin(
                currentSignerSha256 = liveInstalled.currentSignerSha256,
                pinnedSignerSha256 = sl.secrets.getPin(liveInstalled.applicationId),
            )
        ) {
            _state.update {
                it.copy(
                    warning = "Installation blocked: the installed publisher key does not match " +
                        "LocalAndroidStore's trust pin. Review the installed signer first.",
                )
            }
            return
        }
        if (!sl.installer.canRequestInstalls()) {
            _state.update { it.copy(warning = "Grant 'Install unknown apps' first.") }
            sl.installer.openInstallPermissionSettings()
            return
        }
        val key = cardKey(card.info)
        activeJobs[key]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            val cached = sl.appIdCache.get(
                card.info.sourceKey,
                card.info.owner,
                card.info.repo,
            )
            val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
            val safeName = "${card.info.sourceKey}_${card.info.owner}_${card.info.repo}_" +
                "${card.info.tagName}.apk"
            val target = File(
                cacheDir,
                safeName.replace(Regex("[^a-zA-Z0-9._-]"), "_"),
            )
            val referrerUri = android.net.Uri.parse(card.info.asset.browserDownloadUrl)
            var preapprovalSessionId: Int? = null

            try {
                sl.foregroundInstalls.start(
                    info = card.info,
                    apk = target,
                    referrerUrl = referrerUri.toString(),
                )

                // Item 5: Request pre-approval on API 34+ for known updates.
                // Pre-approval prompts the user *before* the download.
                val knownApplicationId = cached?.applicationId
                if (
                    card.status == CardStatus.UpdateAvailable &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    knownApplicationId != null &&
                    sl.installState.info(knownApplicationId) != null
                ) {
                    updateCard(card.info) {
                        it.copy(
                            status = CardStatus.Working,
                            progress = 0f,
                            message = "Requesting pre-approval…",
                        )
                    }
                    val preapprovalResult = sl.installer.createSessionAndRequestPreapproval(
                        applicationId = knownApplicationId,
                        label = card.info.displayName,
                        referrerUri = referrerUri,
                        onSessionCreated = { sessionId ->
                            sl.foregroundInstalls.markPreapproving(key, sessionId)
                        },
                    )
                    when (preapprovalResult) {
                        is PreapprovalSessionResult.Approved -> {
                            sl.logger.info(
                                "Install",
                                "Pre-approval granted for $knownApplicationId " +
                                    "(sessionId=${preapprovalResult.sessionId})",
                            )
                            preapprovalSessionId = preapprovalResult.sessionId
                        }
                        is PreapprovalSessionResult.Declined -> {
                            sl.logger.info(
                                "Install",
                                "Pre-approval declined for $knownApplicationId — " +
                                    "falling back to standard install",
                            )
                        }
                    }
                }
                sl.foregroundInstalls.markDownloading(key, preapprovalSessionId)

                updateCard(card.info) {
                    it.copy(
                        status = CardStatus.Working,
                        progress = 0.01f,
                        message = "Downloading…",
                    )
                }
                sl.github.download(
                    url = card.info.asset.browserDownloadUrl,
                    target = target,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                    expectedDigest = card.info.asset.digest,
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCard(card.info) { it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%") }
                }

                val meta = when (val inspection = sl.apkInspector.inspectResult(target)) {
                    is ApkInspectionResult.Verified -> inspection.metadata
                    is ApkInspectionResult.Rejected -> {
                        preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                        sl.foregroundInstalls.remove(key)
                        sl.logger.error(
                            "Install",
                            "Rejected ${card.info.owner}/${card.info.repo} APK: " +
                                "${inspection.reason.name} (${inspection.diagnostics})",
                        )
                        updateCard(card.info) {
                            it.copy(
                                status = if (inspection.reason.isSignatureFailure) {
                                    CardStatus.SignatureMismatch
                                } else {
                                    CardStatus.Error
                                },
                                message = inspection.reason.userMessage,
                            )
                        }
                        return@launch
                    }
                }

                val expectedInstalled = cached?.applicationId
                    ?.let { sl.installState.info(it) }
                if (
                    expectedInstalled != null &&
                    meta.applicationId != expectedInstalled.applicationId
                ) {
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                    sl.foregroundInstalls.remove(key)
                    sl.audit.installBlocked(
                        card.info.copy(applicationId = meta.applicationId),
                        meta,
                        reason = "application_id_changed",
                    )
                    updateCard(card.info) {
                        it.copy(
                            status = CardStatus.Error,
                            message = "Release package ${meta.applicationId} does not match " +
                                "${expectedInstalled.applicationId}.",
                        )
                    }
                    return@launch
                }

                // Signature pinning — block silent publisher swap.
                val pinned = sl.secrets.getPin(meta.applicationId)
                val installedAlready = sl.installState.info(meta.applicationId) != null
                val pinAccepted = when {
                    pinned.isNullOrEmpty() -> true
                    pinned == meta.signingSha256 -> true
                    pinned in meta.lineageSha256 -> {
                        sl.logger.info(
                            "Install",
                            "Pinned cert $pinned appears in v3 lineage of ${meta.applicationId}; " +
                                "accepting legitimate key rotation to ${meta.signingSha256}"
                        )
                        true
                    }
                    else -> false
                }
                if (!pinAccepted) {
                    val installedSigner = sl.installState.info(meta.applicationId)
                        ?.currentSignerSha256
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                    sl.foregroundInstalls.remove(key)
                    sl.logger.error(
                        "Install",
                        "Signature pin mismatch for ${meta.applicationId}: pinned=$pinned " +
                            "actual=${meta.signingSha256} lineage=${meta.lineageSha256}"
                    )
                    sl.audit.installBlocked(card.info, meta, reason = "signature_pin_mismatch")
                    updateCard(card.info) {
                        it.copy(
                            status = CardStatus.SignatureMismatch,
                            message = "Publisher key changed — install blocked. " +
                                "Possible compromise or legitimate key loss. Review trust details.",
                            publisherTrustDetails = PublisherTrustDetails(
                                source = if (card.info.sourceLabel == card.info.owner) {
                                    card.info.handle
                                } else {
                                    "${card.info.sourceLabel} · ${card.info.handle}"
                                },
                                installedSignerSha256 = installedSigner,
                                storedPinSha256 = requireNotNull(pinned),
                                downloadedMetadata = meta,
                            ),
                        )
                    }
                    return@launch
                }

                if (installedAlready) {
                    sl.appIdCache.recordInspected(card.info, meta)
                    val installedInfo = requireNotNull(sl.installState.info(meta.applicationId))
                    val classifiedStatus = when {
                        meta.versionCode > installedInfo.versionCode ->
                            CardStatus.UpdateAvailable
                        meta.versionCode == installedInfo.versionCode ->
                            CardStatus.ReinstallAvailable
                        else -> CardStatus.DowngradeAvailable
                    }
                    if (card.status != classifiedStatus) {
                        preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                        sl.foregroundInstalls.remove(key)
                        val actionMessage = when (classifiedStatus) {
                            CardStatus.UpdateAvailable ->
                                "Inspected version code ${meta.versionCode} is newer than " +
                                    "installed ${installedInfo.versionCode}. Tap Update to continue."
                            CardStatus.ReinstallAvailable ->
                                "This release has the installed version code " +
                                    "${installedInfo.versionCode}. Tap Reinstall to continue."
                            CardStatus.DowngradeAvailable ->
                                "Release version code ${meta.versionCode} is below installed " +
                                    "${installedInfo.versionCode}. Tap Downgrade to explicitly continue."
                            else -> null
                        }
                        updateCard(card.info) {
                            it.copy(
                                info = it.info.copy(
                                    applicationId = meta.applicationId,
                                    versionCode = meta.versionCode,
                                    versionName = meta.versionName ?: it.info.versionName,
                                ),
                                status = classifiedStatus,
                                installedVersion = installedInfo.versionName,
                                installedVersionCode = installedInfo.versionCode,
                                progress = 0f,
                                message = actionMessage,
                            )
                        }
                        return@launch
                    }
                }

                // Item 34: Pause for permission review when an update requests new dangerous perms.
                if (installedAlready) {
                    val newDangerousPerms = computeNewDangerousPermissions(meta)
                    if (newDangerousPerms.isNotEmpty()) {
                        pendingInstalls[key] = PendingInstallData(target, meta, pinned, installedAlready, preapprovalSessionId, referrerUri)
                        sl.foregroundInstalls.markPermissionReview(
                            key = key,
                            metadata = meta,
                            pinnedSignerSha256 = pinned,
                            installedAlready = installedAlready,
                            preapprovalSessionId = preapprovalSessionId,
                            permissions = newDangerousPerms,
                        )
                        preapprovalSessionId = null // Transfer ownership to pendingInstalls
                        updateCard(card.info) {
                            it.copy(
                                status = CardStatus.PermissionReview,
                                newDangerousPermissions = newDangerousPerms,
                                message = null,
                                progress = 0f,
                            )
                        }
                        return@launch
                    }
                }

                performInstall(card, target, meta, pinned, installedAlready, preapprovalSessionId, referrerUri)
            } catch (t: CancellationException) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.remove(key)
                throw t // Always rethrow so coroutine machinery works correctly.
            } catch (t: Throwable) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.remove(key)
                sl.logger.error("Install", "Install pipeline crashed", t)
                updateCard(card.info) { it.copy(status = CardStatus.Error, message = t.message ?: "install failed") }
            }
        }
        activeJobs[key] = job
        job.invokeOnCompletion { activeJobs.remove(key) }
    }

    /** Cancel an in-flight download/install and reset the card to its pre-working state. */
    fun cancelInstall(card: CardState) {
        val key = cardKey(card.info)
        activeJobs.remove(key)?.cancel()
        cancelPersistedForegroundOperation(key)
        resetCard(card)
    }

    /**
     * Deliberate recovery from an unrelated publisher key. This only replaces the local trust
     * pin; it never resumes or starts an install. The release must be downloaded and reviewed
     * again through the normal pipeline.
     */
    fun replacePublisherPin(
        card: CardState,
        typedApplicationId: String,
        independentlyVerified: Boolean,
    ) {
        val currentCard = _state.value.cards.firstOrNull {
            cardKey(it.info) == cardKey(card.info)
        }
        val details = currentCard?.publisherTrustDetails
        if (currentCard?.status != CardStatus.SignatureMismatch ||
            details == null ||
            !canReplacePublisherPin(details, typedApplicationId, independentlyVerified)
        ) {
            _state.update {
                it.copy(warning = "Publisher trust replacement was not authorized.")
            }
            return
        }

        val meta = details.downloadedMetadata
        val livePin = sl.secrets.getPin(meta.applicationId)
        val liveInstalledSigner = sl.installState.info(meta.applicationId)?.currentSignerSha256
        if (livePin != details.storedPinSha256 ||
            liveInstalledSigner != details.installedSignerSha256
        ) {
            _state.update {
                it.copy(warning = "Publisher trust changed while it was being reviewed. Inspect the release again.")
            }
            return
        }
        if (!sl.audit.publisherPinRecoveryAuthorized(
                info = currentCard.info,
                meta = meta,
                previousPinSha256 = details.storedPinSha256,
                installedSignerSha256 = details.installedSignerSha256,
            )
        ) {
            _state.update {
                it.copy(warning = "Could not write the trust-recovery audit record. The pin was not changed.")
            }
            return
        }
        if (!sl.audit.publisherPinReplacementPending(
                info = currentCard.info,
                meta = meta,
                previousPinSha256 = details.storedPinSha256,
                installedSignerSha256 = details.installedSignerSha256,
            )
        ) {
            _state.update {
                it.copy(warning = "Could not write the trust-replacement pending record. The pin was not changed.")
            }
            return
        }

        val replacement = runCatching {
            sl.secrets.setPin(meta.applicationId, meta.signingSha256)
            check(sl.secrets.getPin(meta.applicationId) == meta.signingSha256) {
                "Pin replacement did not persist"
            }
        }
        if (replacement.isFailure) {
            sl.logger.error("Trust", "Publisher pin replacement failed", replacement.exceptionOrNull())
            _state.update {
                it.copy(warning = "Publisher pin replacement failed. The release remains blocked.")
            }
            return
        }

        if (!sl.audit.publisherPinReplaced(
                info = currentCard.info,
                meta = meta,
                previousPinSha256 = details.storedPinSha256,
                installedSignerSha256 = details.installedSignerSha256,
            )
        ) {
            val rollback = runCatching {
                sl.secrets.setPin(meta.applicationId, details.storedPinSha256)
                check(sl.secrets.getPin(meta.applicationId) == details.storedPinSha256) {
                    "Publisher pin rollback did not persist"
                }
            }
            sl.logger.error("Trust", "Publisher pin replacement audit completion failed", rollback.exceptionOrNull())
            _state.update {
                it.copy(
                    warning = if (rollback.isSuccess) {
                        "Could not write durable trust-replacement evidence. The pin was restored."
                    } else {
                        "Trust replacement is pending durable audit evidence. Do not rely on this pin until the next refresh."
                    },
                )
            }
            return
        }
        sl.logger.warn(
            "Trust",
            "Publisher pin replaced for ${meta.applicationId} after two-step confirmation: " +
                "${details.storedPinSha256} -> ${meta.signingSha256}",
        )
        val installed = sl.installState.info(meta.applicationId)
        if (installed != null) {
            sl.appIdCache.recordInspected(currentCard.info, meta)
        }
        val nextStatus = when {
            installed == null -> CardStatus.NotInstalled
            meta.versionCode > installed.versionCode -> CardStatus.UpdateAvailable
            meta.versionCode == installed.versionCode -> CardStatus.ReinstallAvailable
            else -> CardStatus.DowngradeAvailable
        }
        updateCard(currentCard.info) {
            it.copy(
                info = it.info.copy(
                    applicationId = meta.applicationId,
                    versionCode = meta.versionCode,
                    versionName = meta.versionName ?: it.info.versionName,
                ),
                status = nextStatus,
                installedVersion = installed?.versionName,
                installedVersionCode = installed?.versionCode,
                progress = 0f,
                message = "Publisher pin replaced after explicit confirmation. " +
                    "Review the release, then choose the install action again.",
                publisherTrustDetails = null,
            )
        }
    }

    /** Queue an installed update through UIDT/WorkManager and gentle PackageInstaller constraints. */
    fun queueBackgroundUpdate(card: CardState, notificationsGranted: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
            _state.update {
                it.copy(
                    warning = "Background updates need notifications. Enable them in Settings; " +
                        "foreground install remains available.",
                )
            }
            return
        }
        if (card.status != CardStatus.UpdateAvailable) {
            _state.update {
                it.copy(warning = "Inspect the release and confirm it is a higher version first.")
            }
            return
        }
        if (!sl.installer.canRequestInstalls()) {
            _state.update { it.copy(warning = "Grant 'Install unknown apps' first.") }
            sl.installer.openInstallPermissionSettings()
            return
        }
        val cached = sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )
        val applicationId = card.info.applicationId ?: cached?.applicationId
        val installed = applicationId?.let(sl.installState::info)
        if (applicationId == null || installed == null) {
            _state.update { it.copy(warning = "Queue is only available for installed apps.") }
            return
        }
        if (!signerMatchesPin(installed.currentSignerSha256, sl.secrets.getPin(applicationId))) {
            _state.update {
                it.copy(
                    warning = "Queue blocked: the installed publisher key does not match " +
                        "LocalAndroidStore's trust pin.",
                )
            }
            return
        }
        val queuedInfo = card.info.copy(applicationId = applicationId)
        if (sl.backgroundUpdates.enqueue(queuedInfo)) {
            updateCard(card.info) {
                withQueuedUpdateStatus(
                    it.copy(message = "Queued for gentle background update")
                )
            }
            _state.update { it.copy(warning = "Queued ${card.info.displayName} for background update.") }
        } else {
            _state.update { it.copy(warning = "Could not queue ${card.info.displayName}.") }
        }
    }

    fun cancelBackgroundUpdate(card: CardState) {
        val cached = sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )
        val applicationId = card.info.applicationId ?: cached?.applicationId
        val queuedInfo = card.info.copy(applicationId = applicationId)
        sl.backgroundUpdates.cancel(queuedInfo)
        updateCard(card.info, ::withQueuedUpdateStatus)
        _state.update { it.copy(warning = "Cancelled ${card.info.displayName}'s background update.") }
    }

    /** Item 34: Proceed with an install that was paused at the permission-review gate. */
    fun proceedFromPermissionReview(card: CardState) {
        val key = cardKey(card.info)
        val pending = pendingInstalls.remove(key) ?: return
        val job = viewModelScope.launch(Dispatchers.IO) {
            updateCard(card.info) {
                it.copy(status = CardStatus.Working, progress = 0f, message = "Installing…", newDangerousPermissions = emptyList())
            }
            try {
                performInstall(card, pending.apkFile, pending.meta, pending.pinned, pending.installedAlready, pending.preapprovalSessionId, pending.referrerUri)
            } catch (t: CancellationException) {
                pending.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.remove(key)
                throw t
            } catch (t: Throwable) {
                pending.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.remove(key)
                sl.logger.error("Install", "Install (post-permission-review) crashed", t)
                updateCard(card.info) { it.copy(status = CardStatus.Error, message = t.message ?: "install failed") }
            }
        }
        activeJobs[key] = job
        job.invokeOnCompletion { activeJobs.remove(key) }
    }

    /** Item 34: Cancel permission review and abandon the queued session. */
    fun cancelPermissionReview(card: CardState) {
        val key = cardKey(card.info)
        pendingInstalls.remove(key)?.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
        cancelPersistedForegroundOperation(key)
        resetCard(card)
    }

    /** Item 35: Toggle update-ignore for this app. Rebuilds the card to reflect the new state. */
    fun toggleIgnore(card: CardState) {
        sl.ignoreList.toggle(card.info.handle)
        val cached = sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )
        val hydratedInfo = cached?.applicationId?.let { card.info.copy(applicationId = it) } ?: card.info
        val freshState = buildCardState(hydratedInfo, cached)
        _state.update { ui ->
            ui.copy(cards = ui.cards.map { c ->
                if (c.info.sourceKey == card.info.sourceKey &&
                    c.info.owner == card.info.owner &&
                    c.info.repo == card.info.repo
                ) freshState else c
            })
        }
    }

    /** Item 62: Download the APK and save it to the Downloads folder without installing. */
    fun saveApk(card: CardState) {
        val key = cardKey(card.info)
        activeJobs[key]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            updateCard(card.info) { it.copy(status = CardStatus.Working, progress = 0.01f, message = "Downloading…") }
            val safeTag = card.info.tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val filename = "${card.info.displayName}_${safeTag}.apk"
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
            val target = File(cacheDir, "save_${key.hashCode()}_$filename")
            try {
                sl.github.download(
                    url = card.info.asset.browserDownloadUrl,
                    target = target,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                    expectedDigest = card.info.asset.digest,
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCard(card.info) { it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%") }
                }
                saveToDownloads(filename, target)
                val cached = sl.appIdCache.get(
                    card.info.sourceKey,
                    card.info.owner,
                    card.info.repo,
                )
                val freshState = buildCardState(card.info, cached)
                _state.update { ui ->
                    ui.copy(
                        cards = ui.cards.map { c ->
                            if (c.info.sourceKey == card.info.sourceKey &&
                                c.info.owner == card.info.owner &&
                                c.info.repo == card.info.repo
                            ) freshState else c
                        },
                        warning = "Saved to Downloads: $filename",
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                sl.logger.error("SaveApk", "Save failed", t)
                updateCard(card.info) { it.copy(status = CardStatus.Error, message = t.message ?: "Save failed") }
            } finally {
                target.delete()
                File("${target.absolutePath}.part").delete()
            }
        }
        activeJobs[key] = job
        job.invokeOnCompletion { activeJobs.remove(key) }
    }

    fun uninstall(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        sl.installer.openAppInfo(applicationId)
        sl.audit.uninstallInitiated(applicationId, card.info.handle)
        sl.logger.info("Uninstall", "Opened delete intent for $applicationId")
    }

    fun open(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        val installed = sl.installState.info(applicationId)
        if (installed == null) {
            _state.update { it.copy(warning = "$applicationId is no longer installed.") }
            return
        }
        if (!signerMatchesPin(installed.currentSignerSha256, sl.secrets.getPin(applicationId))) {
            _state.update {
                it.copy(
                    warning = "Opening blocked: the installed publisher key does not match " +
                        "LocalAndroidStore's trust pin.",
                )
            }
            return
        }
        if (!sl.installer.launch(applicationId)) {
            _state.update { it.copy(warning = "Couldn't launch $applicationId — no exported launcher activity?") }
        }
    }

    fun openRepo(card: CardState) {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(card.info.htmlUrl))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        sl.appContext.startActivity(intent)
    }

    fun dismissWarning() = _state.update { it.copy(warning = null) }

    // region Private helpers

    private fun cardKey(info: AppInfo) = "${info.sourceKey}/${info.owner}/${info.repo}"

    private fun cancelPersistedForegroundOperation(key: String) {
        sl.foregroundInstalls.get(key)?.let { operation ->
            operation.preapprovalSessionId?.let(sl.installer::abandonSession)
            operation.installerSessionId?.let(sl.installer::abandonSession)
            sl.foregroundInstalls.remove(key)
        }
    }

    private fun resetCard(card: CardState) {
        val cached = sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )
        val hydratedInfo = cached?.applicationId?.let { card.info.copy(applicationId = it) } ?: card.info
        val freshState = buildCardState(hydratedInfo, cached)
        _state.update { ui ->
            ui.copy(cards = ui.cards.map { c ->
                if (c.info.sourceKey == card.info.sourceKey &&
                    c.info.owner == card.info.owner &&
                    c.info.repo == card.info.repo
                ) freshState else c
            })
        }
    }

    private fun updateCard(info: AppInfo, transform: (CardState) -> CardState) {
        _state.update { ui ->
            ui.copy(cards = ui.cards.map {
                if (it.info.sourceKey == info.sourceKey && it.info.owner == info.owner && it.info.repo == info.repo) {
                    transform(it)
                } else {
                    it
                }
            })
        }
    }

    private fun withQueuedUpdateStatus(card: CardState): CardState {
        val queued = sl.queuedUpdateStatus.get(
            sourceKey = card.info.sourceKey,
            owner = card.info.owner,
            repo = card.info.repo,
        )
        if (queued == null || card.status == CardStatus.Working) {
            return card.copy(queuedUpdateStatus = queued)
        }
        return card.copy(
            queuedUpdateStatus = queued,
            message = queued.message,
        )
    }

    private fun withForegroundInstallState(card: CardState): CardState {
        val operation = sl.foregroundInstalls.get(cardKey(card.info)) ?: return card
        return when (operation.phase) {
            ForegroundInstallPhase.PermissionReview -> card.copy(
                status = CardStatus.PermissionReview,
                progress = 0f,
                message = null,
                newDangerousPermissions = operation.newDangerousPermissions,
            )
            ForegroundInstallPhase.Committing -> card.copy(
                status = CardStatus.Working,
                progress = 1f,
                message = "Waiting for Android to finish installation…",
            )
            ForegroundInstallPhase.Preapproving -> card.copy(
                status = CardStatus.Working,
                progress = 0f,
                message = "Waiting for update pre-approval…",
            )
            ForegroundInstallPhase.Downloading -> card.copy(
                status = CardStatus.Working,
                progress = 0f,
                message = "Recovering download state…",
            )
        }
    }

    private fun catalogNotice(result: CatalogDiscoveryResult): String? {
        val primary = result.issues.firstOrNull() ?: return null
        val details = when (primary.kind) {
            CatalogFailureKind.Tls -> "Android could not authenticate GitHub's secure connection."
            CatalogFailureKind.Authentication -> "Update the personal access token for ${primary.sourceLabel}."
            CatalogFailureKind.Authorization -> "The token for ${primary.sourceLabel} lacks access."
            CatalogFailureKind.RateLimited -> primary.retryAtEpochMillis?.let { resetAt ->
                "GitHub's request limit resets at ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(resetAt))
                }."
            } ?: "GitHub did not provide a usable reset time."
            CatalogFailureKind.Network -> "Check the connection and refresh."
            CatalogFailureKind.Server -> "GitHub reported a temporary server failure."
            CatalogFailureKind.InvalidResponse -> "GitHub returned unreadable release metadata."
            CatalogFailureKind.Unknown -> "Check Activity for technical details."
        }
        val snapshot = primary.snapshotAgeMillis?.let {
            " Showing the last saved catalog (${formatSnapshotAge(it)} old)."
        }.orEmpty()
        val additional = (result.issues.size - 1)
            .takeIf { it > 0 }
            ?.let { " $it additional source${if (it == 1) "" else "s"} failed." }
            .orEmpty()
        return "${primary.message} $details$snapshot$additional".trim()
    }

    private fun formatSnapshotAge(ageMillis: Long): String {
        val minutes = ageMillis / 60_000L
        return when {
            minutes < 1L -> "less than a minute"
            minutes < 60L -> "$minutes min"
            minutes < 1_440L -> "${minutes / 60L} h"
            else -> "${minutes / 1_440L} d"
        }
    }

    /**
     * Item 34: Handle devVerification notice + actual platform install for both the normal
     * path and the post-permission-review path.
     */
    private suspend fun performInstall(
        card: CardState,
        target: File,
        meta: ApkMetadata,
        pinned: String?,
        installedAlready: Boolean,
        preapprovalSessionId: Int?,
        referrerUri: android.net.Uri,
    ) {
        val key = cardKey(card.info)
        val developerVerificationNotice = sl.developerVerification.evaluate(meta)
        sl.logger.warn(
            "DeveloperVerification",
            "Preflight advisory for ${meta.applicationId}: ${developerVerificationNotice.reason}"
        )
        sl.audit.developerVerificationWarned(info = card.info, meta = meta, reason = developerVerificationNotice.reason)
        updateCard(card.info) { it.copy(developerVerificationNotice = developerVerificationNotice) }

        updateCard(card.info) { it.copy(message = "Installing…") }
        val result = if (preapprovalSessionId != null) {
            checkNotNull(
                sl.foregroundInstalls.markCommitting(
                    key = key,
                    metadata = meta,
                    pinnedSignerSha256 = pinned,
                    installedAlready = installedAlready,
                    installerSessionId = preapprovalSessionId,
                ),
            ) { "Could not persist preapproved install session" }
            sl.installer.commitSession(
                sessionId = preapprovalSessionId,
                applicationId = meta.applicationId,
                apk = target,
            )
        } else {
            sl.installer.installApk(
                apk = target,
                applicationId = meta.applicationId,
                firstInstall = !installedAlready,
                referrerUri = referrerUri,
                onSessionCreated = { sessionId ->
                    checkNotNull(
                        sl.foregroundInstalls.markCommitting(
                            key = key,
                            metadata = meta,
                            pinnedSignerSha256 = pinned,
                            installedAlready = installedAlready,
                            installerSessionId = sessionId,
                        ),
                    ) { "Could not persist installer session" }
                },
            )
        }
        when (result) {
            is InstallResult.Success -> {
                sl.foregroundInstalls.get(key)?.let { operation ->
                    ForegroundInstallFinalizer.reconcileCompletedOperation(
                        operation,
                        sl.logger,
                    )
                }
                val installedInfo = sl.installState.info(meta.applicationId)
                updateCard(card.info) { state ->
                    state.copy(
                        info = state.info.copy(
                            applicationId = meta.applicationId,
                            versionCode = meta.versionCode,
                            versionName = meta.versionName ?: state.info.versionName,
                        ),
                        status = CardStatus.Installed,
                        installedVersion = installedInfo?.versionName ?: meta.versionName,
                        installedVersionCode = installedInfo?.versionCode ?: meta.versionCode,
                        progress = 1f,
                        message = null,
                        developerVerificationNotice = null,
                        newDangerousPermissions = emptyList(),
                    )
                }
            }
            is InstallResult.Queued -> {
                sl.foregroundInstalls.remove(key)
                updateCard(card.info) {
                    it.copy(status = CardStatus.UpdateAvailable, progress = 0f, message = "Queued for gentle background update")
                }
            }
            is InstallResult.Failure -> {
                if (result.auditPending) {
                    sl.logger.error(
                        "Install",
                        "Install completed but audit evidence is pending for ${meta.applicationId}",
                    )
                    _state.update {
                        it.copy(
                            warning = "Install completed, but durable audit evidence is pending. " +
                                "Refresh after storage is available.",
                        )
                    }
                    updateCard(card.info) {
                        it.copy(
                            status = CardStatus.Working,
                            progress = 1f,
                            message = "Install complete; recording audit evidence…",
                        )
                    }
                } else {
                    if (sl.foregroundInstalls.get(key) != null) {
                        sl.audit.installFailed(card.info, meta, result.message)
                        sl.logger.warn(
                            "Install",
                            "Install failed for ${meta.applicationId}: ${result.message}",
                        )
                        sl.foregroundInstalls.remove(key)
                    }
                    updateCard(card.info) { it.copy(status = CardStatus.Error, message = result.message) }
                }
            }
        }
    }

    /**
     * Item 34: Returns permissions the APK adds vs the installed version that require
     * explicit user grants (dangerous protection level). Empty if not an update or if the
     * package info cannot be read.
     */
    private fun computeNewDangerousPermissions(meta: ApkMetadata): List<String> {
        val pm = sl.appContext.packageManager
        val installedPerms = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    meta.applicationId,
                    android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_PERMISSIONS.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(meta.applicationId, android.content.pm.PackageManager.GET_PERMISSIONS)
            }
        }.getOrNull()?.requestedPermissions?.toSet() ?: return emptyList()

        return PermissionDiff.newDangerousPermissions(sl.appContext, meta)
            .filter { it !in installedPerms }
    }

    /**
     * Item 62: Copy [source] to the public Downloads folder.
     * API 29+: MediaStore (no permission required).
     * API 26–28: App-scoped external Downloads (no permission required).
     */
    private fun saveToDownloads(filename: String, source: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, filename)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = sl.appContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert failed for $filename")
            sl.foregroundInstalls.addPendingMediaStoreUri(uri)
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: throw java.io.IOException("MediaStore output unavailable for $filename")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                if (resolver.update(uri, values, null, null) != 1) {
                    throw java.io.IOException("Could not publish $filename")
                }
                sl.foregroundInstalls.completePendingMediaStoreUri(uri)
            } catch (throwable: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                sl.foregroundInstalls.completePendingMediaStoreUri(uri)
                throw throwable
            }
        } else {
            // App-scoped external storage — visible in Files app, no permission needed.
            @Suppress("DEPRECATION")
            val downloads = sl.appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw java.io.IOException("External storage unavailable")
            downloads.mkdirs()
            source.copyTo(File(downloads, filename), overwrite = true)
        }
    }

    // endregion
}
