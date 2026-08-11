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
import com.sysadmin.lasstore.data.AccentColor
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.accentForSource
import com.sysadmin.lasstore.data.ApkTransparencyInspector
import com.sysadmin.lasstore.data.ApkTransparencyReport
import com.sysadmin.lasstore.data.DeveloperVerificationNotice
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.GhRelease
import com.sysadmin.lasstore.data.InstallProvenance
import com.sysadmin.lasstore.data.InstalledInfo
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.SourceBranding
import com.sysadmin.lasstore.data.UpdateCadence
import com.sysadmin.lasstore.data.signerMatchesArtifactOrLineage
import com.sysadmin.lasstore.data.signerMatchesPin
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.ApkAssetSelection
import com.sysadmin.lasstore.domain.ApkAssetClassifier
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.domain.aggregateCatalogApps
import com.sysadmin.lasstore.domain.CatalogDiscoveryResult
import com.sysadmin.lasstore.domain.CatalogFailureKind
import com.sysadmin.lasstore.domain.CatalogSourceIssue
import com.sysadmin.lasstore.domain.DiscoveryUseCase
import com.sysadmin.lasstore.domain.ReleaseVersionRelation
import com.sysadmin.lasstore.domain.ReleaseChannel
import com.sysadmin.lasstore.domain.SourceVerificationStatus
import com.sysadmin.lasstore.domain.classifyReleaseVersion
import com.sysadmin.lasstore.domain.sourceVerificationStatus
import com.sysadmin.lasstore.install.InstallResult
import com.sysadmin.lasstore.install.BatchUninstallEntry
import com.sysadmin.lasstore.install.ForegroundInstallFinalizer
import com.sysadmin.lasstore.install.ForegroundInstallPhase
import com.sysadmin.lasstore.install.ExternalLaunchResult
import com.sysadmin.lasstore.install.ArtifactVerificationRejection
import com.sysadmin.lasstore.install.ArtifactVerificationResult
import com.sysadmin.lasstore.install.verifyInstallArtifact
import com.sysadmin.lasstore.install.PermissionDiff
import com.sysadmin.lasstore.install.PreapprovalSessionResult
import com.sysadmin.lasstore.install.QueuedUpdateStatus
import com.sysadmin.lasstore.install.QueuedUpdatePayload
import com.sysadmin.lasstore.install.QueuedUpdatePhase
import com.sysadmin.lasstore.install.safeLaunchExternalIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class CardState(
    val info: AppInfo,
    val status: CardStatus,
    val installedVersion: String? = null,
    val installedVersionCode: Long? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val developerVerificationNotice: DeveloperVerificationNotice? = null,
    val sourceVerification: SourceVerificationStatus = SourceVerificationStatus.Unknown,
    val sourceAccent: AccentColor = AccentColor.Mauve,
    /** New dangerous permissions the update requests vs the installed version (Item 34). */
    val newDangerousPermissions: List<String> = emptyList(),
    /** True when the user has silenced update notifications for this app (Item 35). */
    val isIgnored: Boolean = false,
    val updateCadence: UpdateCadence = UpdateCadence(),
    val queuedUpdateStatus: QueuedUpdateStatus? = null,
    val publisherTrustDetails: PublisherTrustDetails? = null,
    val publisherTrustRecoveryBusy: Boolean = false,
    val unmanagedInstall: UnmanagedInstallDetails? = null,
    val releaseHistory: ReleaseHistoryState? = null,
    val historicalSelection: Boolean = false,
    val alternativeSources: List<AppInfo> = emptyList(),
    val channelPreference: ReleaseChannel? = null,
    val resumableDownloadBytes: Long = 0L,
    val transparencyReport: ApkTransparencyReport? = null,
    val transparencyBusy: Boolean = false,
    val transparencyError: String? = null,
)

data class UnmanagedInstallDetails(
    val applicationId: String,
    val installedVersionName: String?,
    val installedVersionCode: Long,
    val installedSignerSha256: String?,
    val source: String,
)

data class HistoricalRelease(
    val release: GhRelease,
    val info: AppInfo?,
    val inspectedVersionCode: Long? = null,
    val inspectedVersionName: String? = null,
    val inspectedSignerSha256: String? = null,
)

data class ReleaseHistoryState(
    val loading: Boolean = false,
    val releases: List<HistoricalRelease> = emptyList(),
    val nextPage: Int? = 1,
    val error: String? = null,
)

data class CatalogSourceBranding(
    val sourceKey: String,
    val sourceLabel: String,
    val branding: SourceBranding,
    val sourceAccent: AccentColor,
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
    val noEnabledSources: Boolean = false,
    val warning: String? = null,
    val selectedAntiFeatures: Set<String> = emptySet(),
    val hideUnverifiedSources: Boolean = false,
    val sourceBrandings: List<CatalogSourceBranding> = emptyList(),
    val stagedUpdates: List<QueuedUpdatePayload> = emptyList(),
    val batchQueueBusy: Boolean = false,
    val selectionMode: Boolean = false,
    val selectedCardKeys: Set<String> = emptySet(),
)

internal fun catalogCardKey(info: AppInfo): String =
    "${info.sourceKey}/${info.owner}/${info.repo}"

internal fun validatedGitHubRepositoryUri(rawUrl: String): android.net.Uri? {
    val uri = runCatching { android.net.Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase(Locale.US)
    if (
        !uri.scheme.equals("https", ignoreCase = true) ||
        host !in setOf("github.com", "www.github.com") ||
        uri.port != -1 ||
        !uri.userInfo.isNullOrBlank() ||
        uri.pathSegments.count { it.isNotBlank() } < 2
    ) {
        return null
    }
    return uri
}

internal fun preserveActivityResumeContext(previous: CardState, rebuilt: CardState): CardState =
    rebuilt.copy(
        releaseHistory = previous.releaseHistory,
        historicalSelection = previous.historicalSelection,
        transparencyReport = previous.transparencyReport,
        transparencyBusy = previous.transparencyBusy,
        transparencyError = previous.transparencyError,
    )

internal fun reserveUniqueDownloadFile(directory: File, filename: String): File {
    val extensionIndex = filename.lastIndexOf('.').takeIf { it > 0 } ?: filename.length
    val base = filename.substring(0, extensionIndex)
    val extension = filename.substring(extensionIndex)
    for (suffix in 0..9999) {
        val candidateName = if (suffix == 0) {
            filename
        } else {
            "$base ($suffix)$extension"
        }
        val candidate = File(directory, candidateName)
        if (candidate.createNewFile()) return candidate
    }
    throw IOException("No collision-free Downloads filename available for $filename")
}

class CatalogViewModel : ViewModel() {
    private val sl = ServiceLocator
    private val transparencyInspector by lazy { ApkTransparencyInspector(sl.appContext) }
    private val discovery = DiscoveryUseCase(
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

    private val _state = MutableStateFlow(
        CatalogUiState(stagedUpdates = sl.downloadQueue.payloads()),
    )
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    /** Active install jobs keyed by sourceKey/owner/repo. Used for cancellation. */
    private val activeJobs = ConcurrentHashMap<String, Job>()
    /** Generation for the current foreground action; stale callbacks cannot mutate the card. */
    private val activeActionIds = ConcurrentHashMap<String, String>()
    /** One explicit release-history request per catalog card. */
    private val historyJobs = ConcurrentHashMap<String, Job>()
    /** Queue/cancel scheduling runs off the click dispatcher and is serialized per card. */
    private val queueJobs = ConcurrentHashMap<String, Job>()
    /** One serialized confirmation pass for the durable staged-update batch. */
    @Volatile private var batchQueueJob: Job? = null
    /** Publisher-pin recovery performs secret, audit, and cache I/O off the Compose path. */
    private val publisherPinJobs = ConcurrentHashMap<String, Job>()
    @Volatile private var refreshJob: Job? = null
    @Volatile private var resumeReconcileJob: Job? = null
    @Volatile private var refreshGeneration = 0L
    @Volatile private var sourceCandidatesByApplicationId: Map<String, List<AppInfo>> = emptyMap()
    @Volatile private var currentSettings: AppSettings = AppSettings()

    /** APK + metadata held after inspection when waiting for permission review (Item 34). */
    private data class PendingInstallData(
        val apkFile: File,
        val meta: ApkMetadata,
        val pinned: String?,
        val installedAlready: Boolean,
        val preapprovalSessionId: Int?,
        val referrerUri: android.net.Uri,
        val operationId: String,
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
            sl.settings.flow.collectLatest { settings ->
                currentSettings = settings
                _state.update { ui ->
                    ui.copy(
                        hideUnverifiedSources = settings.hideUnverifiedSources,
                        cards = ui.cards.map { card ->
                            card.copy(sourceAccent = accentForSource(settings, card.info.sourceKey))
                        },
                    )
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
                            operationId = operation.operationId,
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

    /** Reconcile state after returning from Android Settings or an external uninstall flow. */
    fun onActivityResumed() {
        refreshInstallPermission()
        reconcileBatchUninstall()
        if (resumeReconcileJob?.isActive != true) {
            val job = viewModelScope.launch(Dispatchers.IO) {
                val rebuilt = _state.value.cards.associateBy(
                    keySelector = { cardKey(it.info) },
                    valueTransform = { card ->
                        buildCardState(
                            card.info,
                            sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo),
                        ).let { rebuiltCard ->
                            preserveActivityResumeContext(card, rebuiltCard)
                        }
                    },
                )
                _state.update { current ->
                    current.copy(
                        cards = current.cards.map { card ->
                            if (hasActiveOperation(card)) {
                                card
                            } else {
                                rebuilt[cardKey(card.info)] ?: card
                            }
                        },
                    )
                }
            }
            resumeReconcileJob = job
            job.invokeOnCompletion {
                if (resumeReconcileJob == job) resumeReconcileJob = null
            }
        }
        refreshIfIdle()
    }

    fun openInstallPermissionSettings() {
        when (val result = sl.installer.openInstallPermissionSettings()) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> _state.update { it.copy(warning = result.message) }
        }
    }

    /** Open Android's per-app language settings for the installed package represented by a card. */
    fun openAppLanguageSettings(card: CardState) {
        val applicationId = card.info.applicationId ?: sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )?.applicationId
        if (applicationId.isNullOrBlank()) {
            _state.update {
                it.copy(warning = "Inspect or install this app before choosing its language.")
            }
            return
        }
        when (val result = sl.installer.openAppLanguageSettings(applicationId)) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> _state.update { it.copy(warning = result.message) }
        }
    }

    /** Open Android's public Developer options entry point for the advanced sideloading flow. */
    fun openAdvancedSideloadingFlow() {
        when (val result = sl.installer.openAdvancedSideloadingSettings()) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> _state.update { it.copy(warning = result.message) }
        }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun toggleAntiFeature(feature: String) {
        _state.update { current ->
            val selected = current.selectedAntiFeatures.toMutableSet()
            if (!selected.add(feature)) selected.remove(feature)
            current.copy(selectedAntiFeatures = selected)
        }
    }

    fun enterSelectionMode(card: CardState) {
        _state.update {
            it.copy(
                selectionMode = true,
                selectedCardKeys = it.selectedCardKeys + catalogCardKey(card.info),
            )
        }
    }

    fun toggleCardSelection(card: CardState) {
        val key = catalogCardKey(card.info)
        _state.update { current ->
            val selected = current.selectedCardKeys.toMutableSet()
            if (!selected.add(key)) selected.remove(key)
            current.copy(
                selectionMode = selected.isNotEmpty(),
                selectedCardKeys = selected,
            )
        }
    }

    fun selectAllCards(keys: List<String>) {
        val allowed = keys.toSet()
        _state.update { current ->
            val selected = if (current.selectedCardKeys.containsAll(allowed) && allowed.isNotEmpty()) {
                emptySet()
            } else {
                allowed
            }
            current.copy(
                selectionMode = selected.isNotEmpty(),
                selectedCardKeys = selected,
            )
        }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(selectionMode = false, selectedCardKeys = emptySet()) }
    }

    /** Start the existing guarded foreground installer for every selected installable card. */
    fun installSelected() {
        val selected = selectedCards().filter { card ->
            card.status in setOf(
                CardStatus.NotInstalled,
                CardStatus.ReleaseAvailable,
                CardStatus.UpdateAvailable,
                CardStatus.ReinstallAvailable,
                CardStatus.DowngradeAvailable,
            )
        }
        exitSelectionMode()
        if (selected.isEmpty()) {
            _state.update { it.copy(warning = "No selected card is ready for installation.") }
            return
        }
        selected.forEach(::install)
        _state.update {
            it.copy(warning = "Started installation for ${selected.size} selected card(s).")
        }
    }

    /** Stage every selected higher-version managed update into the durable batch queue. */
    fun stageSelectedUpdates() {
        val selected = selectedCards().filter { it.status == CardStatus.UpdateAvailable }
        exitSelectionMode()
        if (selected.isEmpty()) {
            _state.update { it.copy(warning = "No selected card has a managed update to stage.") }
            return
        }
        selected.forEach(::stageBackgroundUpdate)
        _state.update {
            it.copy(warning = "Staged ${selected.size} selected update(s). Confirm the batch when ready.")
        }
    }

    /** Queue selected uninstall confirmations; Android still requires one confirmation per app. */
    fun uninstallSelected() {
        val entries = selectedCards()
            .filter { card -> card.status in INSTALLED_CARD_STATUSES }
            .mapNotNull { card ->
                val applicationId = card.info.applicationId
                    ?: sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo)
                        ?.applicationId
                applicationId?.let {
                    BatchUninstallEntry(
                        applicationId = it,
                        displayName = card.info.displayName,
                        handle = card.info.handle,
                    )
                }
            }
            .distinctBy(BatchUninstallEntry::applicationId)
        exitSelectionMode()
        if (entries.isEmpty()) {
            _state.update { it.copy(warning = "No selected card is currently installed.") }
            return
        }
        sl.batchUninstalls.begin(entries)
        launchNextBatchUninstall()
    }

    private fun selectedCards(): List<CardState> {
        val selected = _state.value.selectedCardKeys
        return _state.value.cards.filter { catalogCardKey(it.info) in selected }
    }

    private fun reconcileBatchUninstall() {
        val current = sl.batchUninstalls.peek() ?: return
        if (!sl.batchUninstalls.isAwaitingConfirmation()) {
            launchNextBatchUninstall()
            return
        }
        if (sl.installState.info(current.applicationId) == null) {
            sl.batchUninstalls.remove(current.applicationId)
            sl.batchUninstalls.markAwaitingConfirmation(false)
            if (sl.batchUninstalls.peek() == null) {
                sl.batchUninstalls.clear()
                _state.update { it.copy(warning = "Batch uninstall completed.") }
            } else {
                launchNextBatchUninstall()
            }
        } else {
            sl.batchUninstalls.clear()
            _state.update {
                it.copy(warning = "Batch uninstall stopped; ${current.displayName} remains installed.")
            }
        }
    }

    private fun launchNextBatchUninstall() {
        val current = sl.batchUninstalls.peek() ?: return
        if (sl.installState.info(current.applicationId) == null) {
            sl.batchUninstalls.remove(current.applicationId)
            launchNextBatchUninstall()
            return
        }
        sl.batchUninstalls.markAwaitingConfirmation(true)
        when (val result = sl.installer.openAppInfo(current.applicationId)) {
            ExternalLaunchResult.Started -> {
                sl.audit.uninstallInitiated(current.applicationId, current.handle)
                _state.update {
                    it.copy(
                        warning = "Confirm uninstall for ${current.displayName}; the next selected app opens after you return.",
                    )
                }
            }
            is ExternalLaunchResult.Failed -> {
                sl.batchUninstalls.clear()
                _state.update { it.copy(warning = result.message) }
            }
        }
    }

    fun setChannelPreference(card: CardState, channel: ReleaseChannel?) {
        sl.channelPreferences.set(card.info, channel)
        updateCard(card.info) { current -> current.copy(channelPreference = channel) }
        refresh()
    }

    /** Stage a safe installed update without scheduling transport work yet. */
    fun stageBackgroundUpdate(card: CardState) {
        if (card.historicalSelection) {
            _state.update { it.copy(warning = "Historical releases require an explicit foreground install.") }
            return
        }
        if (card.info.assetChoices.size > 1) {
            _state.update { it.copy(warning = "Choose an APK variant before adding it to the batch.") }
            return
        }
        if (card.status != CardStatus.UpdateAvailable) {
            _state.update { it.copy(warning = "Inspect the release and confirm it is a higher version first.") }
            return
        }
        if (card.queuedUpdateStatus?.isPending == true) {
            _state.update { it.copy(warning = "This update is already queued for background work.") }
            return
        }
        val cached = sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo)
        val applicationId = card.info.applicationId ?: cached?.applicationId
        val installed = applicationId?.let(sl.installState::info)
        if (applicationId == null || installed == null) {
            _state.update { it.copy(warning = "Only installed managed apps can be staged for a batch.") }
            return
        }
        if (!signerMatchesPin(installed.currentSignerSha256, sl.secrets.getPin(applicationId))) {
            _state.update {
                it.copy(warning = "Batch staging blocked: the installed publisher key does not match the trust pin.")
            }
            return
        }
        val payload = QueuedUpdatePayload.from(card.info.copy(applicationId = applicationId))
        try {
            val added = sl.downloadQueue.stage(payload)
            _state.update {
                it.copy(
                    stagedUpdates = sl.downloadQueue.payloads(),
                    warning = if (added) {
                        "Staged ${card.info.displayName}. Confirm the batch when ready."
                    } else {
                        "${card.info.displayName} is already staged."
                    },
                )
            }
        } catch (throwable: Throwable) {
            sl.logger.warn("DownloadQueue", "Could not stage ${card.info.displayName}: ${throwable.message}")
            _state.update { it.copy(warning = "Could not stage ${card.info.displayName} for the batch.") }
        }
    }

    fun removeStagedUpdate(payload: QueuedUpdatePayload) {
        sl.downloadQueue.remove(payload.workName)
        _state.update { it.copy(stagedUpdates = sl.downloadQueue.payloads()) }
    }

    fun clearStagedUpdates() {
        sl.downloadQueue.clear()
        _state.update { it.copy(stagedUpdates = emptyList()) }
    }

    /** Confirm the durable staged batch and enqueue each payload with its persisted generation. */
    fun confirmStagedUpdates(notificationsGranted: Boolean = true) {
        val staged = sl.downloadQueue.payloads()
        if (staged.isEmpty()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsGranted) {
            _state.update {
                it.copy(
                    warning = "Batch background updates need notifications. Enable them in Settings first.",
                )
            }
            return
        }
        if (!sl.installer.canRequestInstalls()) {
            _state.update { it.copy(warning = "Grant 'Install unknown apps' before confirming the batch.") }
            openInstallPermissionSettings()
            return
        }
        batchQueueJob?.cancel()
        _state.update { it.copy(batchQueueBusy = true, warning = "Confirming ${staged.size} staged updates…") }
        val job = viewModelScope.launch(Dispatchers.IO) {
            var enqueued = 0
            var skipped = 0
            var failed = 0
            staged.forEach { payload ->
                val existing = sl.queuedUpdateStatus.get(payload)
                val alreadySubmitted = existing?.generationId == payload.generationId &&
                    existing.phase != QueuedUpdatePhase.Failed &&
                    existing.phase != QueuedUpdatePhase.Cancelled
                if (alreadySubmitted) {
                    sl.downloadQueue.remove(payload.workName)
                    skipped += 1
                    return@forEach
                }
                if (sl.backgroundUpdates.enqueue(payload)) {
                    sl.downloadQueue.remove(payload.workName)
                    enqueued += 1
                } else {
                    failed += 1
                }
            }
            withContext(Dispatchers.Main) {
                _state.update {
                    it.copy(
                        stagedUpdates = sl.downloadQueue.payloads(),
                        batchQueueBusy = false,
                        warning = buildString {
                            if (enqueued > 0) append("Queued $enqueued staged update(s). ")
                            if (skipped > 0) append("Recovered $skipped already-submitted update(s). ")
                            if (failed > 0) append("$failed update(s) remain staged for retry.")
                        }.trim().ifBlank { "No staged updates were submitted." },
                    )
                }
            }
        }
        batchQueueJob = job
        job.invokeOnCompletion { if (batchQueueJob == job) batchQueueJob = null }
    }

    fun refresh() {
        refreshJob?.cancel()
        val generation = ++refreshGeneration
        val job = viewModelScope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(
                    refreshing = true,
                    errorMessage = null,
                    noEnabledSources = false,
                )
            }
            try {
                val settings = sl.settings.flow.first()
                currentSettings = settings
                ensureActive()
                val sourceBrandings = coroutineScope {
                    val configured = settings.sources
                        .filter { it.enabled && it.brandingUrl.isNotBlank() }
                        .map { source ->
                            async {
                                runCatching {
                                    sl.sourceBranding.fetch(source.brandingUrl)
                                }.onFailure { failure ->
                                    sl.logger.warn(
                                        "Catalog",
                                        "Could not read branding for ${source.displayName}: " +
                                            (failure.message ?: "request failed"),
                                    )
                                }.getOrNull()?.let { branding ->
                                    CatalogSourceBranding(
                                        sourceKey = source.key,
                                        sourceLabel = source.displayName,
                                        branding = branding,
                                        sourceAccent = accentForSource(settings, source.key),
                                    )
                                }
                            }
                        } + settings.fdroidSources
                        .filter { it.enabled && it.brandingUrl.isNotBlank() }
                        .map { source ->
                            async {
                                runCatching {
                                    sl.sourceBranding.fetch(source.brandingUrl)
                                }.onFailure { failure ->
                                    sl.logger.warn(
                                        "Catalog",
                                        "Could not read branding for ${source.displayName}: " +
                                            (failure.message ?: "request failed"),
                                    )
                                }.getOrNull()?.let { branding ->
                                    CatalogSourceBranding(
                                        sourceKey = source.key,
                                        sourceLabel = source.displayName,
                                        branding = branding,
                                        sourceAccent = accentForSource(settings, source.key),
                                    )
                                }
                            }
                        }
                    configured.awaitAll().filterNotNull()
                }
                ensureActive()
                val enabledSources = settings.sources.filter { it.enabled }
                val enabledFdroidSources = settings.fdroidSources.filter { it.enabled }
                val discoveryResult = try {
                    discovery.discover(settings.sources, settings.fdroidSources)
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
                val infos = aggregateCatalogInfos(discoveryResult.apps)
                sl.logger.info(
                    "Catalog",
                    "Discovered ${infos.size} APK-bearing repos across " +
                        "${enabledSources.size + enabledFdroidSources.size} enabled sources"
                )
                // Hydrate applicationId from the persistent cache so UpdateAvailable survives cold starts.
                val cards = buildList {
                    infos.forEach { info ->
                        ensureActive()
                        val cached = sl.appIdCache.get(info.sourceKey, info.owner, info.repo)
                        add(buildCardState(info, cached))
                    }
                }
                sl.wearUpdates.publishUpdateCount(
                    cards.count { it.status == CardStatus.UpdateAvailable },
                )
                ensureActive()
                val catalogNotice = catalogNotice(discoveryResult)
                _state.update { current ->
                    if (generation != refreshGeneration) {
                        current
                    } else {
                        current.copy(
                            refreshing = false,
                            cards = cards,
                            hideUnverifiedSources = settings.hideUnverifiedSources,
                            sourceBrandings = sourceBrandings,
                            noEnabledSources = enabledSources.isEmpty() && enabledFdroidSources.isEmpty(),
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

    private fun refreshIfIdle() {
        if (refreshJob?.isActive != true) refresh()
    }

    /** Derive card state from package metadata and an inspected release, never from a tag. */
    private fun buildCardState(info: AppInfo, cached: AppIdEntry? = null): CardState {
        val applicationId = info.applicationId ?: cached?.applicationId
        val installed = applicationId?.let { sl.installState.info(it) }
        val reconciled = cached?.let { entry ->
            installed?.let { installedInfo ->
                sl.appIdCache.reconcileInstalled(entry, installedInfo)
            } ?: entry
        }
        val isIgnored = sl.ignoreList.isIgnored(info.handle)
        val baseState = when {
            installed == null -> CardState(info = info, status = CardStatus.NotInstalled)
            reconciled?.provenance == InstallProvenance.EXTERNAL_UNMANAGED ->
                unmanagedCardState(info, reconciled, installed)
            info.assetChoices.size > 1 -> {
                val pinnedSignerSha256 = sl.secrets.getPin(installed.applicationId)
                if (!signerMatchesPin(installed.currentSignerSha256, pinnedSignerSha256)) {
                    CardState(
                        info = info.copy(applicationId = applicationId),
                        status = CardStatus.SignatureMismatch,
                        installedVersion = installed.versionName,
                        installedVersionCode = installed.versionCode,
                        isIgnored = isIgnored,
                        message = "Installed publisher key does not match LocalAndroidStore's " +
                            "trust pin. Review the installed signer before choosing an APK.",
                    )
                } else {
                    CardState(
                        info = info.copy(applicationId = applicationId),
                        status = CardStatus.ReleaseAvailable,
                        installedVersion = installed.versionName,
                        installedVersionCode = installed.versionCode,
                        isIgnored = isIgnored,
                        message = "Multiple standalone APKs match this release. Choose one before downloading.",
                    )
                }
            }
            else -> {
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
        val alternatives = applicationId
            ?.let { sourceCandidatesByApplicationId[it.lowercase(Locale.US)] }
            .orEmpty()
            .filterNot { candidate -> cardKey(candidate) == cardKey(info) }
        return withForegroundInstallState(
            withQueuedUpdateStatus(
                baseState.copy(
                    updateCadence = sl.updateCadences.get(
                        info.copy(applicationId = applicationId),
                    ),
                    sourceVerification = sourceVerificationStatus(
                        applicationId = applicationId,
                        knownSignerSha256 = installed?.currentSignerSha256
                            ?: reconciled?.inspectedRelease?.signerSha256,
                        pinnedSignerSha256 = applicationId?.let(sl.secrets::getPin),
                    ),
                    sourceAccent = accentForSource(currentSettings, info.sourceKey),
                    alternativeSources = alternatives,
                    channelPreference = sl.channelPreferences.get(info),
                    resumableDownloadBytes = sl.foregroundInstalls.partialDownloadSize(info),
                ),
            ),
        )
    }

    private fun aggregateCatalogInfos(infos: List<AppInfo>): List<AppInfo> {
        val hydrated = infos.map { info ->
            if (info.applicationId != null) {
                info
            } else {
                sl.appIdCache.get(info.sourceKey, info.owner, info.repo)
                    ?.applicationId
                    ?.let { info.copy(applicationId = it) }
                    ?: info
            }
        }
        val aggregated = aggregateCatalogApps(
            infos = hydrated,
            preferredSourceFor = sl.preferredSources::get,
            candidateAllowed = { candidate ->
                candidate.minSdk == null || candidate.minSdk <= Build.VERSION.SDK_INT
            },
        )
        sourceCandidatesByApplicationId = aggregated
            .filter { !it.primary.applicationId.isNullOrBlank() }
            .associate { it.primary.applicationId!!.lowercase(Locale.US) to it.candidates }
        return aggregated.map { it.primary }
    }

    fun selectPreferredSource(card: CardState, sourceKey: String) {
        if (card.status == CardStatus.Working) {
            _state.update { it.copy(warning = "Finish the current action before changing source preference.") }
            return
        }
        val applicationId = card.info.applicationId
            ?: sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo)?.applicationId
        if (applicationId.isNullOrBlank()) {
            _state.update {
                it.copy(warning = "Inspect this APK once before pinning a preferred source.")
            }
            return
        }
        val candidate = (listOf(card.info) + card.alternativeSources)
            .firstOrNull { it.sourceKey == sourceKey }
        if (candidate == null) {
            _state.update { it.copy(warning = "That source is no longer available in the catalog.") }
            return
        }
        sl.preferredSources.set(applicationId, candidate.sourceKey)
        val cached = sl.appIdCache.get(candidate.sourceKey, candidate.owner, candidate.repo)
        val freshState = buildCardState(candidate.copy(applicationId = applicationId), cached)
        _state.update { current ->
            current.copy(
                cards = current.cards.map { existing ->
                    if (sameApplication(existing.info, applicationId)) freshState else existing
                },
                warning = "Preferred source set to ${candidate.sourceLabel}.",
            )
        }
    }

    private fun sameApplication(info: AppInfo, applicationId: String): Boolean =
        info.applicationId?.equals(applicationId, ignoreCase = true) == true ||
            sourceCandidatesByApplicationId[applicationId.lowercase(Locale.US)]
                ?.any { candidate -> cardKey(candidate) == cardKey(info) } == true

    private fun unmanagedCardState(
        info: AppInfo,
        entry: AppIdEntry,
        installed: InstalledInfo,
    ): CardState {
        val inspected = entry.inspectedRelease
            ?.takeIf { it.asset == com.sysadmin.lasstore.data.ReleaseAssetIdentity.from(info) }
        val hydratedInfo = info.copy(
            applicationId = installed.applicationId,
            versionCode = inspected?.versionCode ?: info.versionCode,
            versionName = inspected?.versionName ?: info.versionName,
        )
        return CardState(
            info = hydratedInfo,
            status = CardStatus.Unmanaged,
            installedVersion = installed.versionName,
            installedVersionCode = installed.versionCode,
            message = "Installed elsewhere. Confirm adoption before LocalAndroidStore manages updates.",
            unmanagedInstall = UnmanagedInstallDetails(
                applicationId = installed.applicationId,
                installedVersionName = installed.versionName,
                installedVersionCode = installed.versionCode,
                installedSignerSha256 = installed.currentSignerSha256,
                source = if (info.sourceLabel == info.owner) info.handle else {
                    "${info.sourceLabel} · ${info.handle}"
                },
            ),
        )
    }

    fun install(card: CardState, allowUnmanagedReplacement: Boolean = false) {
        if (card.status == CardStatus.Unmanaged && !allowUnmanagedReplacement) {
            _state.update {
                it.copy(warning = "Confirm adoption first, or choose the explicit manual install action.")
            }
            return
        }
        if (card.info.assetChoices.size > 1) {
            _state.update { it.copy(warning = "Choose an APK variant before downloading.") }
            return
        }
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
            openInstallPermissionSettings()
            return
        }
        val key = cardKey(card.info)
        cancelActiveAction(key)
        pendingInstalls.remove(key)
        cancelPersistedForegroundOperation(key)
        val operationId = newActionId()
        val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
        val safeName = "${card.info.sourceKey}_${card.info.owner}_${card.info.repo}_" +
            "${card.info.tagName}_$operationId.apk"
        val target = File(
            cacheDir,
            safeName.replace(Regex("[^a-zA-Z0-9._-]"), "_"),
        )
        val referrerUri = android.net.Uri.parse(card.info.asset.browserDownloadUrl)
        sl.foregroundInstalls.start(
            info = card.info,
            apk = target,
            referrerUrl = referrerUri.toString(),
            operationId = operationId,
        )
        claimAction(key, operationId)

        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val cached = sl.appIdCache.get(
                card.info.sourceKey,
                card.info.owner,
                card.info.repo,
            )
            var preapprovalSessionId: Int? = null

            try {
                // Item 5: Request pre-approval on API 34+ for known updates.
                // Pre-approval prompts the user *before* the download.
                val knownApplicationId = cached?.applicationId
                if (
                    card.status == CardStatus.UpdateAvailable &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                    knownApplicationId != null &&
                    sl.installState.info(knownApplicationId) != null &&
                    !sl.installer.isSilentInstallActive()
                ) {
                    updateCardForAction(card.info, operationId) {
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
                        operationId = operationId,
                        onSessionCreated = { sessionId ->
                            sl.foregroundInstalls.markPreapproving(key, operationId, sessionId)
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
                if (!sl.foregroundInstalls.isCurrent(key, operationId)) return@launch
                sl.foregroundInstalls.markDownloading(key, operationId, preapprovalSessionId)

                updateCardForAction(card.info, operationId) {
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
                    partialFile = sl.foregroundInstalls.partialDownloadFile(card.info),
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCardForAction(card.info, operationId) {
                        it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%")
                    }
                }
                if (!sl.foregroundInstalls.isCurrent(key, operationId)) return@launch

                val meta = when (val inspection = sl.apkInspector.inspectResult(target)) {
                    is ApkInspectionResult.Verified -> inspection.metadata
                    is ApkInspectionResult.Rejected -> {
                        preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                        sl.foregroundInstalls.removeIfCurrent(key, operationId)
                        sl.logger.error(
                            "Install",
                            "Rejected ${card.info.owner}/${card.info.repo} APK: " +
                                "${inspection.reason.name} (${inspection.diagnostics})",
                        )
                        updateCardForAction(card.info, operationId) {
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
                val installedInfo = sl.installState.info(meta.applicationId)
                val installedAlready = installedInfo != null
                val verification = verifyInstallArtifact(
                    expectedApplicationId = expectedInstalled?.applicationId,
                    installedInfo = installedInfo,
                    metadata = meta,
                    pinnedSignerSha256 = sl.secrets.getPin(meta.applicationId),
                )
                if (verification is ArtifactVerificationResult.Rejected) {
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                    sl.foregroundInstalls.removeIfCurrent(key, operationId)
                    val reason = when (verification.reason) {
                        ArtifactVerificationRejection.PackageIdentity -> "application_id_changed"
                        ArtifactVerificationRejection.InstalledSigner -> "installed_signer_mismatch"
                        ArtifactVerificationRejection.PublisherPin -> "signature_pin_mismatch"
                    }
                    sl.audit.installBlocked(
                        card.info.copy(applicationId = meta.applicationId),
                        meta,
                        reason = reason,
                    )
                    sl.logger.error("Install", verification.message)
                    val trustDetails = when (verification.reason) {
                        ArtifactVerificationRejection.PublisherPin -> PublisherTrustDetails(
                            source = if (card.info.sourceLabel == card.info.owner) {
                                card.info.handle
                            } else {
                                "${card.info.sourceLabel} · ${card.info.handle}"
                            },
                            installedSignerSha256 = verification.installedSignerSha256,
                            storedPinSha256 = requireNotNull(verification.pinnedSignerSha256),
                            downloadedMetadata = meta,
                        )
                        ArtifactVerificationRejection.InstalledSigner ->
                            verification.pinnedSignerSha256
                                ?.takeIf { it.isNotBlank() }
                                ?.let { storedPin ->
                                    PublisherTrustDetails(
                                        source = if (card.info.sourceLabel == card.info.owner) {
                                            card.info.handle
                                        } else {
                                            "${card.info.sourceLabel} · ${card.info.handle}"
                                        },
                                        installedSignerSha256 = verification.installedSignerSha256,
                                        storedPinSha256 = storedPin,
                                        downloadedMetadata = meta,
                                    )
                                }
                        ArtifactVerificationRejection.PackageIdentity -> null
                    }
                    updateCardForAction(card.info, operationId) {
                        it.copy(
                            status = if (verification.reason == ArtifactVerificationRejection.PackageIdentity) {
                                CardStatus.Error
                            } else {
                                CardStatus.SignatureMismatch
                            },
                            message = verification.message,
                            publisherTrustDetails = trustDetails,
                        )
                    }
                    return@launch
                }
                val accepted = verification as ArtifactVerificationResult.Accepted
                val pinned = accepted.pinnedSignerSha256
                if (accepted.lineageRotationAccepted) {
                    sl.logger.info(
                        "Install",
                        "Pinned cert $pinned appears in v3 lineage of ${meta.applicationId}; " +
                            "accepting legitimate key rotation to ${meta.signingSha256}",
                    )
                }

                val externalObservation = installedInfo?.takeIf {
                    pinned.isNullOrBlank() &&
                        (cached == null || cached.provenance == InstallProvenance.EXTERNAL_UNMANAGED)
                }
                if (externalObservation != null && !allowUnmanagedReplacement) {
                    val observed = sl.appIdCache.recordExternalObservation(
                        info = card.info,
                        metadata = meta,
                        installed = externalObservation,
                    )
                    if (!sl.audit.externalAppObserved(card.info, meta, externalObservation)) {
                        sl.logger.warn(
                            "Install",
                            "External install observation was persisted without audit completion",
                        )
                    }
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                    sl.foregroundInstalls.removeIfCurrent(key, operationId)
                    val unmanaged = unmanagedCardState(
                        info = card.info.copy(
                            applicationId = meta.applicationId,
                            versionCode = meta.versionCode,
                            versionName = meta.versionName ?: card.info.versionName,
                        ),
                        entry = observed,
                        installed = externalObservation,
                    )
                    updateCardForAction(card.info, operationId) { unmanaged }
                    return@launch
                }

                if (installedAlready) {
                    sl.appIdCache.recordInspected(card.info, meta)
                    val currentInstalled = requireNotNull(installedInfo)
                    val classifiedStatus = when {
                        meta.versionCode > currentInstalled.versionCode ->
                            CardStatus.UpdateAvailable
                        meta.versionCode == currentInstalled.versionCode ->
                            CardStatus.ReinstallAvailable
                        else -> CardStatus.DowngradeAvailable
                    }
                    if (
                        card.status != classifiedStatus &&
                        !(allowUnmanagedReplacement && card.status == CardStatus.Unmanaged)
                    ) {
                        preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                        sl.foregroundInstalls.removeIfCurrent(key, operationId)
                        val actionMessage = when (classifiedStatus) {
                            CardStatus.UpdateAvailable ->
                                "Inspected version code ${meta.versionCode} is newer than " +
                                    "installed ${currentInstalled.versionCode}. Tap Update to continue."
                            CardStatus.ReinstallAvailable ->
                                "This release has the installed version code " +
                                    "${currentInstalled.versionCode}. Tap Reinstall to continue."
                            CardStatus.DowngradeAvailable ->
                                "Release version code ${meta.versionCode} is below installed " +
                                    "${currentInstalled.versionCode}. Tap Downgrade to explicitly continue."
                            else -> null
                        }
                        updateCardForAction(card.info, operationId) {
                            it.copy(
                                info = it.info.copy(
                                    applicationId = meta.applicationId,
                                    versionCode = meta.versionCode,
                                    versionName = meta.versionName ?: it.info.versionName,
                                ),
                                status = classifiedStatus,
                                installedVersion = currentInstalled.versionName,
                                installedVersionCode = currentInstalled.versionCode,
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
                        pendingInstalls[key] = PendingInstallData(
                            apkFile = target,
                            meta = meta,
                            pinned = pinned,
                            installedAlready = installedAlready,
                            preapprovalSessionId = preapprovalSessionId,
                            referrerUri = referrerUri,
                            operationId = operationId,
                        )
                        sl.foregroundInstalls.markPermissionReview(
                            key = key,
                            operationId = operationId,
                            metadata = meta,
                            pinnedSignerSha256 = pinned,
                            installedAlready = installedAlready,
                            preapprovalSessionId = preapprovalSessionId,
                            permissions = newDangerousPerms,
                        )
                        preapprovalSessionId = null // Transfer ownership to pendingInstalls
                        updateCardForAction(card.info, operationId) {
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

                performInstall(
                    card = card,
                    target = target,
                    meta = meta,
                    pinned = pinned,
                    installedAlready = installedAlready,
                    preapprovalSessionId = preapprovalSessionId,
                    referrerUri = referrerUri,
                    operationId = operationId,
                )
            } catch (t: CancellationException) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.removeIfCurrent(key, operationId)
                throw t // Always rethrow so coroutine machinery works correctly.
            } catch (t: Throwable) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.removeIfCurrent(key, operationId)
                sl.logger.error("Install", "Install pipeline crashed", t)
                updateCardForAction(card.info, operationId) {
                    it.copy(
                        status = CardStatus.Error,
                        message = t.message ?: "install failed",
                        resumableDownloadBytes = sl.foregroundInstalls.partialDownloadSize(card.info),
                    )
                }
            }
        }
        registerActionJob(key, operationId, job)
    }

    /** Confirm external provenance, enroll the observed signer, and enable managed updates. */
    fun adopt(card: CardState) {
        val details = card.unmanagedInstall ?: return
        val cached = sl.appIdCache.get(
            card.info.sourceKey,
            card.info.owner,
            card.info.repo,
        )
        val installed = sl.installState.info(details.applicationId)
        val signer = installed?.currentSignerSha256
        if (
            cached?.provenance != InstallProvenance.EXTERNAL_UNMANAGED ||
            installed == null ||
            signer.isNullOrBlank() ||
            signer != details.installedSignerSha256 ||
            cached.inspectedRelease?.let {
                signerMatchesArtifactOrLineage(
                    currentSignerSha256 = signer,
                    expectedSignerSha256 = it.signerSha256,
                    lineageSha256 = it.lineageSha256,
                )
            } != true
        ) {
            _state.update {
                it.copy(
                    warning = "The installed package changed. Refresh and review adoption again.",
                )
            }
            refresh()
            return
        }
        if (!sl.secrets.getPin(details.applicationId).isNullOrBlank()) {
            _state.update {
                it.copy(
                    warning = "Adoption is blocked because a publisher pin already exists. Refresh to review trust.",
                )
            }
            refresh()
            return
        }
        if (!sl.audit.externalAppAdoptionPending(card.info, installed)) {
            _state.update {
                it.copy(warning = "Adoption could not be recorded in the install journal.")
            }
            return
        }

        val adopted = runCatching {
            sl.secrets.setPin(details.applicationId, signer)
            check(sl.secrets.getPin(details.applicationId) == signer) {
                "Signer pin enrollment did not persist"
            }
            checkNotNull(
                sl.appIdCache.adoptExternal(
                    sourceKey = card.info.sourceKey,
                    owner = card.info.owner,
                    repo = card.info.repo,
                    installed = installed,
                ),
            ) { "External install record was not available for adoption" }
        }
        if (adopted.isFailure) {
            runCatching { sl.secrets.clearPin(details.applicationId) }
            sl.logger.error("Adoption", "External app adoption failed", adopted.exceptionOrNull())
            _state.update {
                it.copy(
                    warning = "Adoption failed; the app remains unmanaged and background updates stay blocked.",
                )
            }
            return
        }

        val adoptedEntry = adopted.getOrThrow()
        val freshState = buildCardState(
            info = card.info.copy(applicationId = details.applicationId),
            cached = adoptedEntry,
        )
        val auditComplete = sl.audit.externalAppAdopted(card.info, installed)
        _state.update { ui ->
            ui.copy(
                cards = ui.cards.map { current ->
                    if (cardKey(current.info) == cardKey(card.info)) freshState else current
                },
                warning = if (auditComplete) {
                    "${card.info.displayName} adopted. Future background updates are now enabled."
                } else {
                    "${card.info.displayName} adopted, but the completion journal entry is pending."
                },
            )
        }
    }

    /** Cancel an in-flight download/install and reset the card to its pre-working state. */
    fun cancelInstall(card: CardState) {
        val key = cardKey(card.info)
        val actionId = cancelActiveAction(key)
        cancelPersistedForegroundOperation(key, actionId)
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
        val key = cardKey(card.info)
        val currentCard = _state.value.cards.firstOrNull {
            cardKey(it.info) == key
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
        if (publisherPinJobs[key]?.isActive == true) {
            _state.update { it.copy(warning = "Publisher trust replacement is already in progress.") }
            return
        }

        val meta = details.downloadedMetadata
        _state.update { ui ->
            ui.copy(
                warning = "Updating publisher trust record…",
                cards = ui.cards.map { current ->
                    if (cardKey(current.info) == key) {
                        current.copy(publisherTrustRecoveryBusy = true)
                    } else {
                        current
                    }
                },
            )
        }
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (!isCurrentPublisherTrustReview(key, meta.signingSha256)) {
                    publishTrustWarning(
                        "Publisher trust changed while it was being reviewed. Inspect the release again.",
                    )
                    return@launch
                }
                val livePin = sl.secrets.getPin(meta.applicationId)
                val liveInstalledSigner = sl.installState.info(meta.applicationId)?.currentSignerSha256
                if (livePin != details.storedPinSha256 ||
                    liveInstalledSigner != details.installedSignerSha256
                ) {
                    publishTrustWarning(
                        "Publisher trust changed while it was being reviewed. Inspect the release again.",
                    )
                    return@launch
                }
                if (!sl.audit.publisherPinRecoveryAuthorized(
                        info = currentCard.info,
                        meta = meta,
                        previousPinSha256 = details.storedPinSha256,
                        installedSignerSha256 = details.installedSignerSha256,
                    )
                ) {
                    publishTrustWarning(
                        "Could not write the trust-recovery audit record. The pin was not changed.",
                    )
                    return@launch
                }
                if (!sl.audit.publisherPinReplacementPending(
                        info = currentCard.info,
                        meta = meta,
                        previousPinSha256 = details.storedPinSha256,
                        installedSignerSha256 = details.installedSignerSha256,
                    )
                ) {
                    publishTrustWarning(
                        "Could not write the trust-replacement pending record. The pin was not changed.",
                    )
                    return@launch
                }

                val replacement = runCatching {
                    sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                    check(sl.secrets.getPin(meta.applicationId) == meta.signingSha256) {
                        "Pin replacement did not persist"
                    }
                }
                if (replacement.isFailure) {
                    sl.logger.error("Trust", "Publisher pin replacement failed", replacement.exceptionOrNull())
                    publishTrustWarning("Publisher pin replacement failed. The release remains blocked.")
                    return@launch
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
                    sl.logger.error(
                        "Trust",
                        "Publisher pin replacement audit completion failed",
                        rollback.exceptionOrNull(),
                    )
                    publishTrustWarning(
                        if (rollback.isSuccess) {
                            "Could not write durable trust-replacement evidence. The pin was restored."
                        } else {
                            "Trust replacement is pending durable audit evidence. Do not rely on this pin until the next refresh."
                        },
                    )
                    return@launch
                }
                sl.logger.warn(
                    "Trust",
                    "Publisher pin replaced for ${meta.applicationId} after two-step confirmation: " +
                        "${details.storedPinSha256} -> ${meta.signingSha256}",
                )
                val installed = sl.installState.info(meta.applicationId)
                if (installed != null) {
                    runCatching { sl.appIdCache.recordInspected(currentCard.info, meta) }
                        .onFailure {
                            sl.logger.warn(
                                "Trust",
                                "Could not refresh the inspected release after pin replacement " +
                                    "(${it::class.simpleName ?: "unknown"})",
                            )
                        }
                }
                val nextStatus = when {
                    installed == null -> CardStatus.NotInstalled
                    meta.versionCode > installed.versionCode -> CardStatus.UpdateAvailable
                    meta.versionCode == installed.versionCode -> CardStatus.ReinstallAvailable
                    else -> CardStatus.DowngradeAvailable
                }
                withContext(Dispatchers.Main) {
                    val latest = _state.value.cards.firstOrNull { cardKey(it.info) == key }
                    if (
                        latest == null ||
                        latest.status != CardStatus.SignatureMismatch ||
                        latest.publisherTrustDetails?.downloadedMetadata?.signingSha256 !=
                            meta.signingSha256
                    ) {
                        return@withContext
                    }
                    updateCard(latest.info) {
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
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                sl.logger.error("Trust", "Publisher pin replacement failed", throwable)
                publishTrustWarning("Publisher pin replacement failed. The release remains blocked.")
            }
        }
        publisherPinJobs[key] = job
        job.invokeOnCompletion {
            publisherPinJobs.remove(key, job)
            _state.update { ui ->
                ui.copy(
                    cards = ui.cards.map { current ->
                        if (cardKey(current.info) == key) {
                            current.copy(publisherTrustRecoveryBusy = false)
                        } else {
                            current
                        }
                    },
                )
            }
        }
        job.start()
    }

    /** Queue an installed update through UIDT/WorkManager and gentle PackageInstaller constraints. */
    fun queueBackgroundUpdate(card: CardState, notificationsGranted: Boolean = true) {
        if (card.historicalSelection) {
            _state.update {
                it.copy(warning = "Historical releases require an explicit foreground install.")
            }
            return
        }
        if (card.info.assetChoices.size > 1) {
            _state.update { it.copy(warning = "Choose an APK variant before queueing an update.") }
            return
        }
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
        val key = cardKey(card.info)
        if (queueJobs[key]?.isActive == true) {
            _state.update { it.copy(warning = "A background queue action is already in progress.") }
            return
        }
        _state.update { it.copy(warning = "Queueing ${card.info.displayName}…") }
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                if (!sl.installer.canRequestInstalls()) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(warning = "Grant 'Install unknown apps' first.") }
                        openInstallPermissionSettings()
                    }
                    return@launch
                }
                val cached = sl.appIdCache.get(
                    card.info.sourceKey,
                    card.info.owner,
                    card.info.repo,
                )
                val applicationId = card.info.applicationId ?: cached?.applicationId
                val installed = applicationId?.let(sl.installState::info)
                if (applicationId == null || installed == null) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(warning = "Queue is only available for installed apps.") }
                    }
                    return@launch
                }
                if (!signerMatchesPin(installed.currentSignerSha256, sl.secrets.getPin(applicationId))) {
                    withContext(Dispatchers.Main) {
                        _state.update {
                            it.copy(
                                warning = "Queue blocked: the installed publisher key does not match " +
                                    "LocalAndroidStore's trust pin.",
                            )
                        }
                    }
                    return@launch
                }
                val queuedInfo = card.info.copy(applicationId = applicationId)
                val queued = sl.backgroundUpdates.enqueue(queuedInfo)
                withContext(Dispatchers.Main) {
                    if (queued) {
                        updateCard(card.info) {
                            withQueuedUpdateStatus(
                                it.copy(message = "Queued for gentle background update")
                            )
                        }
                        _state.update {
                            it.copy(warning = "Queued ${card.info.displayName} for background update.")
                        }
                    } else {
                        _state.update { it.copy(warning = "Could not queue ${card.info.displayName}.") }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(warning = "Could not queue ${card.info.displayName}. Try again.")
                    }
                }
            }
        }
        queueJobs[key] = job
        job.invokeOnCompletion { queueJobs.remove(key, job) }
        job.start()
    }

    fun cancelBackgroundUpdate(card: CardState) {
        val key = cardKey(card.info)
        if (queueJobs[key]?.isActive == true) {
            _state.update { it.copy(warning = "The queue action is still being scheduled.") }
            return
        }
        _state.update { it.copy(warning = "Cancelling ${card.info.displayName}…") }
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                val cached = sl.appIdCache.get(
                    card.info.sourceKey,
                    card.info.owner,
                    card.info.repo,
                )
                val applicationId = card.info.applicationId ?: cached?.applicationId
                val queuedInfo = card.info.copy(applicationId = applicationId)
                val cancelled = sl.backgroundUpdates.cancel(queuedInfo)
                withContext(Dispatchers.Main) {
                    if (cancelled) {
                        updateCard(card.info, ::withQueuedUpdateStatus)
                        _state.update {
                            it.copy(warning = "Cancelled ${card.info.displayName}'s background update.")
                        }
                    } else {
                        _state.update {
                            it.copy(
                                warning = "Could not cancel ${card.info.displayName}'s background update. " +
                                    "Try again.",
                            )
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(warning = "Could not cancel ${card.info.displayName}. Try again.")
                    }
                }
            }
        }
        queueJobs[key] = job
        job.invokeOnCompletion { queueJobs.remove(key, job) }
        job.start()
    }

    /** Item 34: Proceed with an install that was paused at the permission-review gate. */
    fun proceedFromPermissionReview(card: CardState) {
        val key = cardKey(card.info)
        val pending = pendingInstalls.remove(key) ?: return
        if (!sl.foregroundInstalls.isCurrent(key, pending.operationId)) return
        cancelActiveAction(key)
        claimAction(key, pending.operationId)
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            updateCardForAction(card.info, pending.operationId) {
                it.copy(status = CardStatus.Working, progress = 0f, message = "Installing…", newDangerousPermissions = emptyList())
            }
            try {
                performInstall(
                    card = card,
                    target = pending.apkFile,
                    meta = pending.meta,
                    pinned = pending.pinned,
                    installedAlready = pending.installedAlready,
                    preapprovalSessionId = pending.preapprovalSessionId,
                    referrerUri = pending.referrerUri,
                    operationId = pending.operationId,
                )
            } catch (t: CancellationException) {
                pending.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.removeIfCurrent(key, pending.operationId)
                throw t
            } catch (t: Throwable) {
                pending.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                sl.foregroundInstalls.removeIfCurrent(key, pending.operationId)
                sl.logger.error("Install", "Install (post-permission-review) crashed", t)
                updateCardForAction(card.info, pending.operationId) {
                    it.copy(status = CardStatus.Error, message = t.message ?: "install failed")
                }
            }
        }
        registerActionJob(key, pending.operationId, job)
    }

    /** Item 34: Cancel permission review and abandon the queued session. */
    fun cancelPermissionReview(card: CardState) {
        val key = cardKey(card.info)
        val pending = pendingInstalls.remove(key)
        pending?.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
        cancelPersistedForegroundOperation(key, pending?.operationId)
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

    fun setUpdateCadence(card: CardState, cadence: UpdateCadence) {
        sl.updateCadences.set(card.info, cadence)
        updateCard(card.info) { current -> current.copy(updateCadence = cadence) }
        _state.update {
            it.copy(warning = "${card.info.displayName} update cadence set to ${cadence.mode.name.lowercase(Locale.US)}")
        }
    }

    /** Item 62: Download the APK and save it to the Downloads folder without installing. */
    fun saveApk(card: CardState) {
        if (card.info.assetChoices.size > 1) {
            _state.update { it.copy(warning = "Choose an APK variant before downloading.") }
            return
        }
        val key = cardKey(card.info)
        cancelActiveAction(key)
        pendingInstalls.remove(key)
        cancelPersistedForegroundOperation(key)
        val actionId = newActionId()
        claimAction(key, actionId)
        val job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            updateCardForAction(card.info, actionId) {
                it.copy(status = CardStatus.Working, progress = 0.01f, message = "Downloading…")
            }
            val safeTag = card.info.tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val filename = "${card.info.displayName}_${safeTag}.apk"
                .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
            val target = File(cacheDir, "save_${actionId}_$filename")
            try {
                sl.github.download(
                    url = card.info.asset.browserDownloadUrl,
                    target = target,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                    expectedDigest = card.info.asset.digest,
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCardForAction(card.info, actionId) {
                        it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%")
                    }
                }
                if (!ownsAction(key, actionId)) return@launch
                val savedFilename = saveToDownloads(filename, target)
                if (!ownsAction(key, actionId)) return@launch
                val cached = sl.appIdCache.get(
                    card.info.sourceKey,
                    card.info.owner,
                    card.info.repo,
                )
                val freshState = buildCardState(card.info, cached)
                if (ownsAction(key, actionId)) {
                    _state.update { ui ->
                        ui.copy(
                            cards = ui.cards.map { c ->
                                if (c.info.sourceKey == card.info.sourceKey &&
                                    c.info.owner == card.info.owner &&
                                    c.info.repo == card.info.repo
                                ) freshState else c
                            },
                            warning = "Saved to Downloads: $savedFilename",
                        )
                    }
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                sl.logger.error("SaveApk", "Save failed", t)
                updateCardForAction(card.info, actionId) {
                    it.copy(status = CardStatus.Error, message = t.message ?: "Save failed")
                }
            } finally {
                target.delete()
                File("${target.absolutePath}.part").delete()
            }
        }
        registerActionJob(key, actionId, job)
    }

    /**
     * Inspect the installed APK (or an APK retained by the foreground coordinator) locally.
     * Transparency never downloads an unverified artifact or sends APK bytes to a third party.
     */
    fun inspectTransparency(card: CardState) {
        if (card.transparencyBusy) return
        updateCard(card.info) {
            it.copy(transparencyBusy = true, transparencyError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val apk = localTransparencyApk(card)
                    ?: error("Install this release before opening its transparency report.")
                val report = transparencyInspector.inspect(apk)
                val expectedPackage = card.info.applicationId
                    ?: sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo)
                        ?.applicationId
                if (
                    expectedPackage != null &&
                    PACKAGE_NAME_PATTERN.matches(expectedPackage) &&
                    report.metadata.applicationId != expectedPackage
                ) {
                    error(
                        "The installed APK package ${report.metadata.applicationId} does not match " +
                            "the catalog package $expectedPackage.",
                    )
                }
                updateCard(card.info) {
                    it.copy(
                        transparencyReport = report,
                        transparencyBusy = false,
                        transparencyError = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                updateCard(card.info) { it.copy(transparencyBusy = false) }
                throw cancellation
            } catch (throwable: Throwable) {
                sl.logger.warn(
                    "Transparency",
                    "Could not inspect ${card.info.handle}: ${throwable.message}",
                )
                updateCard(card.info) {
                    it.copy(
                        transparencyBusy = false,
                        transparencyError = throwable.message
                            ?: "APK transparency inspection failed.",
                    )
                }
            }
        }
    }

    private fun localTransparencyApk(card: CardState): File? {
        sl.foregroundInstalls.get(cardKey(card.info))
            ?.let { operation -> sl.foregroundInstalls.apkFile(operation) }
            ?.takeIf(File::isFile)
            ?.let { return it }
        val applicationId = card.info.applicationId
            ?: sl.appIdCache.get(card.info.sourceKey, card.info.owner, card.info.repo)
                ?.applicationId
            ?: return null
        val sourcePath = runCatching {
            sl.appContext.packageManager.getApplicationInfo(applicationId, 0).sourceDir
        }.getOrNull() ?: return null
        return File(sourcePath).takeIf(File::isFile)
    }

    fun uninstall(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        when (val result = sl.installer.openAppInfo(applicationId)) {
            ExternalLaunchResult.Started -> {
                sl.audit.uninstallInitiated(applicationId, card.info.handle)
                sl.logger.info("Uninstall", "Opened delete intent for $applicationId")
            }
            is ExternalLaunchResult.Failed -> _state.update { it.copy(warning = result.message) }
        }
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
        when (val result = sl.installer.launch(applicationId)) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> {
                if (sl.installState.info(applicationId) == null) resetCard(card)
                _state.update { it.copy(warning = result.message) }
            }
        }
    }

    fun openRepo(card: CardState) {
        val uri = validatedGitHubRepositoryUri(card.info.htmlUrl)
        if (uri == null) {
            _state.update {
                it.copy(warning = "Repository link rejected: only HTTPS GitHub repository links are allowed.")
            }
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        when (
            val result = safeLaunchExternalIntent(
                intent = intent,
                canResolve = { candidate ->
                    candidate.resolveActivity(sl.appContext.packageManager) != null
                },
                start = { candidate -> sl.appContext.startActivity(candidate) },
                failureMessage = "Couldn't open the GitHub repository.",
            )
        ) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> _state.update { it.copy(warning = result.message) }
        }
    }

    /** Load a bounded, paged release history only after an explicit user request. */
    fun loadReleaseHistory(card: CardState, append: Boolean = false) {
        val key = cardKey(card.info)
        val current = _state.value.cards.firstOrNull { cardKey(it.info) == key } ?: card
        val existing = current.releaseHistory ?: ReleaseHistoryState()
        if (existing.loading || (append && existing.nextPage == null)) return
        val page = if (append) existing.nextPage ?: return else 1
        val starting = if (append) existing else ReleaseHistoryState()
        updateCard(card.info) {
            it.copy(releaseHistory = starting.copy(loading = true, error = null))
        }

        historyJobs[key]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = sl.settings.flow.first()
                val source = settings.sources.firstOrNull { it.key == card.info.sourceKey }
                val pageResult = sl.github.listReleaseHistory(
                    owner = card.info.owner,
                    repo = card.info.repo,
                    includePrereleases = source?.showPrereleases ?: card.info.prerelease,
                    page = page,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                    sourceKey = card.info.sourceKey,
                )
                val entries = pageResult.releases.map { release ->
                    historicalRelease(card.info, release)
                }
                updateCard(card.info) { currentCard ->
                    val history = currentCard.releaseHistory ?: starting
                    currentCard.copy(
                        releaseHistory = history.copy(
                            loading = false,
                            releases = (history.releases + entries)
                                .distinctBy { it.release.tagName },
                            nextPage = pageResult.page
                                .plus(1)
                                .takeIf { pageResult.hasMore },
                            error = null,
                        ),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                sl.logger.error("Catalog", "Release history failed for ${card.info.handle}", throwable)
                updateCard(card.info) { currentCard ->
                    val history = currentCard.releaseHistory ?: starting
                    currentCard.copy(
                        releaseHistory = history.copy(
                            loading = false,
                            error = throwable.message ?: "Could not load release history.",
                        ),
                    )
                }
            }
        }
        historyJobs[key] = job
        job.invokeOnCompletion { historyJobs.remove(key, job) }
    }

    fun selectHistoricalRelease(card: CardState, historical: HistoricalRelease) {
        val selectedInfo = historical.info
        if (selectedInfo == null) {
            _state.update {
                it.copy(warning = "This historical release does not contain a supported standalone APK.")
            }
            return
        }
        if (!sl.audit.historicalReleaseSelected(selectedInfo)) {
            _state.update {
                it.copy(warning = "The historical release selection could not be recorded in Activity.")
            }
            return
        }
        val cached = sl.appIdCache.get(
            selectedInfo.sourceKey,
            selectedInfo.owner,
            selectedInfo.repo,
        )
        val selectedState = buildCardState(selectedInfo, cached)
        val history = _state.value.cards
            .firstOrNull { cardKey(it.info) == cardKey(card.info) }
            ?.releaseHistory
            ?: card.releaseHistory
        updateCard(card.info) {
            selectedState.copy(
                releaseHistory = history,
                historicalSelection = true,
                message = "Historical release selected. Inspect it before installing.",
            )
        }
    }

    fun selectAsset(card: CardState, asset: GhAsset) {
        if (asset !in card.info.assetChoices) return
        val selectedInfo = card.info.copy(
            asset = asset,
            assetChoices = emptyList(),
        )
        val cached = sl.appIdCache.get(
            selectedInfo.sourceKey,
            selectedInfo.owner,
            selectedInfo.repo,
        )
        val freshState = buildCardState(selectedInfo, cached)
        _state.update { ui ->
            ui.copy(cards = ui.cards.map { current ->
                if (cardKey(current.info) == cardKey(card.info)) freshState else current
            })
        }
    }

    fun dismissWarning() = _state.update { it.copy(warning = null) }

    private fun historicalRelease(cardInfo: AppInfo, release: GhRelease): HistoricalRelease {
        val info = appInfoForRelease(cardInfo, release)
        val cached = info?.let {
            sl.appIdCache.get(it.sourceKey, it.owner, it.repo)
        }
        val inspected = info?.let { candidate ->
            cached?.inspectedRelease?.takeIf {
                it.asset == com.sysadmin.lasstore.data.ReleaseAssetIdentity.from(candidate)
            }
        }
        return HistoricalRelease(
            release = release,
            info = info,
            inspectedVersionCode = inspected?.versionCode,
            inspectedVersionName = inspected?.versionName,
            inspectedSignerSha256 = inspected?.signerSha256,
        )
    }

    private fun appInfoForRelease(cardInfo: AppInfo, release: GhRelease): AppInfo? {
        val selection = ApkAssetClassifier.classify(
            assets = release.assets,
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
        )
        val asset: GhAsset
        val choices: List<GhAsset>
        when (selection) {
            ApkAssetSelection.Unavailable -> return null
            is ApkAssetSelection.Selected -> {
                asset = selection.asset
                choices = emptyList()
            }
            is ApkAssetSelection.SelectionRequired -> {
                asset = selection.candidates.first()
                choices = selection.candidates
            }
        }
        return cardInfo.copy(
            displayName = cardInfo.displayName,
            tagName = release.tagName,
            versionName = release.tagName.removePrefix("v").removePrefix("V"),
            versionCode = null,
            asset = asset,
            publishedAt = release.publishedAt,
            prerelease = release.prerelease,
            releaseBody = release.body?.takeIf { it.isNotBlank() },
            assetChoices = choices,
            htmlUrl = release.htmlUrl,
        )
    }

    // region Private helpers

    private fun isCurrentPublisherTrustReview(key: String, downloadedSignerSha256: String): Boolean =
        _state.value.cards.firstOrNull { cardKey(it.info) == key }?.let { current ->
            current.status == CardStatus.SignatureMismatch &&
                current.publisherTrustDetails?.downloadedMetadata?.signingSha256 ==
                    downloadedSignerSha256
        } == true

    private suspend fun publishTrustWarning(message: String) {
        withContext(Dispatchers.Main) {
            _state.update { it.copy(warning = message) }
        }
    }

    private fun cardKey(info: AppInfo) = catalogCardKey(info)

    private fun hasActiveOperation(card: CardState): Boolean {
        val key = cardKey(card.info)
        return activeJobs[key] != null || sl.foregroundInstalls.get(key) != null
    }

    private fun newActionId(): String = UUID.randomUUID().toString()

    private fun claimAction(key: String, actionId: String) {
        activeActionIds[key] = actionId
    }

    private fun registerActionJob(key: String, actionId: String, job: Job) {
        activeJobs[key] = job
        job.invokeOnCompletion {
            activeJobs.remove(key, job)
            activeActionIds.remove(key, actionId)
        }
        job.start()
    }

    private fun cancelActiveAction(key: String): String? {
        val actionId = activeActionIds.remove(key)
        activeJobs.remove(key)?.cancel()
        return actionId
    }

    private fun ownsAction(key: String, actionId: String): Boolean =
        activeActionIds[key] == actionId

    private fun updateCardForAction(
        info: AppInfo,
        actionId: String,
        transform: (CardState) -> CardState,
    ) {
        if (ownsAction(cardKey(info), actionId)) updateCard(info, transform)
    }

    private fun cancelPersistedForegroundOperation(key: String, expectedOperationId: String? = null) {
        sl.foregroundInstalls.get(key)?.let { operation ->
            if (expectedOperationId != null && operation.operationId != expectedOperationId) return@let
            operation.preapprovalSessionId?.let(sl.installer::abandonSession)
            operation.installerSessionId?.let(sl.installer::abandonSession)
            if (expectedOperationId == null) {
                sl.foregroundInstalls.remove(key)
            } else {
                sl.foregroundInstalls.removeIfCurrent(key, expectedOperationId)
            }
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
            CatalogFailureKind.Truncated ->
                "Narrow this source with a topic filter in Settings, then refresh."
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

    private companion object {
        private val PACKAGE_NAME_PATTERN = Regex(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
        )
        private val INSTALLED_CARD_STATUSES = setOf(
            CardStatus.Unmanaged,
            CardStatus.Installed,
            CardStatus.ReleaseAvailable,
            CardStatus.UpdateAvailable,
            CardStatus.ReinstallAvailable,
            CardStatus.DowngradeAvailable,
        )
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
        operationId: String,
    ) {
        val key = cardKey(card.info)
        if (!ownsAction(key, operationId) || !sl.foregroundInstalls.isCurrent(key, operationId)) return
        val developerVerificationNotice = sl.developerVerification.evaluate(meta)
        sl.logger.warn(
            "DeveloperVerification",
            "Preflight advisory for ${meta.applicationId}: ${developerVerificationNotice.reason}"
        )
        sl.audit.developerVerificationWarned(info = card.info, meta = meta, reason = developerVerificationNotice.reason)
        updateCardForAction(card.info, operationId) {
            it.copy(developerVerificationNotice = developerVerificationNotice)
        }

        updateCardForAction(card.info, operationId) { it.copy(message = "Installing…") }
        val result = if (preapprovalSessionId != null) {
            checkNotNull(
                sl.foregroundInstalls.markCommitting(
                    key = key,
                    operationId = operationId,
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
                operationId = operationId,
            )
        } else {
            sl.installer.installApk(
                apk = target,
                applicationId = meta.applicationId,
                firstInstall = !installedAlready,
                referrerUri = referrerUri,
                operationId = operationId,
                onSessionCreated = { sessionId ->
                    checkNotNull(
                        sl.foregroundInstalls.markCommitting(
                            key = key,
                            operationId = operationId,
                            metadata = meta,
                            pinnedSignerSha256 = pinned,
                            installedAlready = installedAlready,
                            installerSessionId = sessionId,
                        ),
                    ) { "Could not persist installer session" }
                },
            )
        }
        if (!ownsAction(key, operationId)) return
        when (result) {
            is InstallResult.Success -> {
                sl.foregroundInstalls.get(key)
                    ?.takeIf { it.operationId == operationId }
                    ?.let { operation ->
                    ForegroundInstallFinalizer.reconcileCompletedOperation(
                        operation,
                        sl.logger,
                    )
                }
                val installedInfo = sl.installState.info(meta.applicationId)
                updateCardForAction(card.info, operationId) { state ->
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
                        sourceVerification = sourceVerificationStatus(
                            applicationId = meta.applicationId,
                            knownSignerSha256 = installedInfo?.currentSignerSha256
                                ?: meta.signingSha256,
                            pinnedSignerSha256 = sl.secrets.getPin(meta.applicationId),
                        ),
                        newDangerousPermissions = emptyList(),
                    )
                }
            }
            is InstallResult.Queued -> {
                sl.foregroundInstalls.removeIfCurrent(key, operationId)
                updateCardForAction(card.info, operationId) {
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
                    updateCardForAction(card.info, operationId) {
                        it.copy(
                            status = CardStatus.Working,
                            progress = 1f,
                            message = "Install complete; recording audit evidence…",
                        )
                    }
                } else {
                    if (sl.foregroundInstalls.get(key)?.operationId == operationId) {
                        sl.audit.installFailed(card.info, meta, result.message)
                        sl.logger.warn(
                            "Install",
                            "Install failed for ${meta.applicationId}: ${result.message}",
                        )
                        sl.foregroundInstalls.removeIfCurrent(key, operationId)
                    }
                    updateCardForAction(card.info, operationId) {
                        it.copy(status = CardStatus.Error, message = result.message)
                    }
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
    private fun saveToDownloads(filename: String, source: File): String {
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
                return filename
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
            val destination = reserveUniqueDownloadFile(downloads, filename)
            try {
                source.inputStream().use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
                return destination.name
            } catch (throwable: Throwable) {
                destination.delete()
                throw throwable
            }
        }
    }

    // endregion
}
