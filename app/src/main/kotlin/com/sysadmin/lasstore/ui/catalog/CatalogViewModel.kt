package com.sysadmin.lasstore.ui.catalog

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.DeveloperVerificationNotice
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.domain.DiscoveryUseCase
import com.sysadmin.lasstore.install.InstallResult
import com.sysadmin.lasstore.install.PreapprovalSessionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class CardState(
    val info: AppInfo,
    val status: CardStatus,
    val installedVersion: String? = null,
    val installedVersionCode: Long? = null,
    val progress: Float = 0f,
    val message: String? = null,
    val developerVerificationNotice: DeveloperVerificationNotice? = null,
)

data class CatalogUiState(
    val refreshing: Boolean = false,
    val cards: List<CardState> = emptyList(),
    val searchQuery: String = "",
    val canRequestInstalls: Boolean = true,
    val errorMessage: String? = null,
    val warning: String? = null,
)

class CatalogViewModel : ViewModel() {
    private val sl = ServiceLocator
    private val discovery = DiscoveryUseCase(sl.github, sl.logger) { sourceKey ->
        sl.settings.getPat(sourceKey)
    }

    private val _state = MutableStateFlow(CatalogUiState())
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    /** Active install jobs keyed by sourceKey/owner/repo. Used for cancellation. */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        refreshInstallPermission()
        refresh()
    }

    fun refreshInstallPermission() {
        _state.update { it.copy(canRequestInstalls = sl.installer.canRequestInstalls()) }
    }

    fun openInstallPermissionSettings() = sl.installer.openInstallPermissionSettings()

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(refreshing = true, errorMessage = null) }
            val settings = sl.settings.flow.first()
            val enabledSources = settings.sources.filter { it.enabled }
            val infos = runCatching { discovery.discover(settings.sources) }
                .onFailure { sl.logger.error("Catalog", "discover failed", it) }
                .getOrElse { emptyList() }
            sl.logger.info(
                "Catalog",
                "Discovered ${infos.size} APK-bearing repos across ${enabledSources.size} enabled sources"
            )
            // Hydrate applicationId from the persistent cache so UpdateAvailable survives cold starts.
            val cards = infos.map { info ->
                val cached = sl.appIdCache.get(info.owner, info.repo)
                buildCardState(info, cached)
            }
            _state.update { it.copy(refreshing = false, cards = cards) }
        }
    }

    /**
     * Derive card display state.
     *
     * [cached] supplies the applicationId and the tag that was last installed via LAS.
     * Without it (cold start before first install), [info.applicationId] is always null
     * so the card would incorrectly show NotInstalled.
     */
    private fun buildCardState(info: AppInfo, cached: AppIdEntry? = null): CardState {
        val applicationId = info.applicationId ?: cached?.applicationId
        val installed = applicationId?.let { sl.installState.info(it) }
        return when {
            installed == null -> CardState(info = info, status = CardStatus.NotInstalled)
            else -> {
                // Compare latest release tag against the tag that was installed.
                // We can't compare versionCodes without downloading the new APK, so tagName
                // is the reliable proxy (GitHub release tags change with every new release).
                val updateAvailable = cached != null && info.tagName != cached.installedTagName
                val status = if (updateAvailable) CardStatus.UpdateAvailable else CardStatus.Installed
                CardState(
                    info = info.copy(applicationId = applicationId),
                    status = status,
                    installedVersion = installed.versionName,
                    installedVersionCode = installed.versionCode,
                )
            }
        }
    }

    fun install(card: CardState) {
        if (!sl.installer.canRequestInstalls()) {
            _state.update { it.copy(warning = "Grant 'Install unknown apps' first.") }
            sl.installer.openInstallPermissionSettings()
            return
        }
        val key = cardKey(card.info)
        activeJobs[key]?.cancel()

        val job = viewModelScope.launch(Dispatchers.IO) {
            val cached = sl.appIdCache.get(card.info.owner, card.info.repo)

            // Item 5: Request pre-approval on API 34+ for known updates.
            // Pre-approval prompts the user *before* the download, reducing perceived latency.
            // Falls back silently to the normal flow on older APIs or if the user declines.
            var preapprovalSessionId: Int? = null
            val knownApplicationId = cached?.applicationId
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                knownApplicationId != null &&
                sl.installState.info(knownApplicationId) != null
            ) {
                updateCard(card.info) { it.copy(status = CardStatus.Working, progress = 0f, message = "Requesting pre-approval…") }
                val referrer = android.net.Uri.parse(card.info.asset.browserDownloadUrl)
                val preapprovalResult = sl.installer.createSessionAndRequestPreapproval(
                    applicationId = knownApplicationId,
                    label = card.info.displayName,
                    referrerUri = referrer,
                )
                when (preapprovalResult) {
                    is PreapprovalSessionResult.Approved -> {
                        sl.logger.info("Install", "Pre-approval granted for $knownApplicationId (sessionId=${preapprovalResult.sessionId})")
                        preapprovalSessionId = preapprovalResult.sessionId
                    }
                    is PreapprovalSessionResult.Declined -> {
                        sl.logger.info("Install", "Pre-approval declined for $knownApplicationId — falling back to standard install")
                    }
                }
            }

            updateCard(card.info) { it.copy(status = CardStatus.Working, progress = 0.01f, message = "Downloading…") }
            try {
                val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
                val target = File(cacheDir, "${card.info.owner}_${card.info.repo}_${card.info.tagName}.apk")
                sl.github.download(
                    url = card.info.asset.browserDownloadUrl,
                    target = target,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCard(card.info) { it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%") }
                }

                val meta = sl.apkInspector.inspect(target)
                if (meta == null) {
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                    sl.logger.error("Install", "ApkInspector returned null for ${target.absolutePath}")
                    updateCard(card.info) { it.copy(status = CardStatus.Error, message = "APK metadata read failed") }
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
                    preapprovalSessionId?.let { sl.installer.abandonSession(it) }
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
                                "Possible MITM or repo takeover. Install manually if you trust this update.",
                        )
                    }
                    return@launch
                }

                val developerVerificationNotice = sl.developerVerification.evaluate(meta)
                if (developerVerificationNotice != null) {
                    sl.logger.warn(
                        "DeveloperVerification",
                        "Preflight warning for ${meta.applicationId}: " +
                            developerVerificationNotice.reason
                    )
                    sl.audit.developerVerificationWarned(
                        info = card.info,
                        meta = meta,
                        reason = developerVerificationNotice.reason,
                    )
                    updateCard(card.info) {
                        it.copy(developerVerificationNotice = developerVerificationNotice)
                    }
                }

                updateCard(card.info) { it.copy(message = "Installing…") }
                val result = if (preapprovalSessionId != null) {
                    sl.installer.commitSession(sessionId = preapprovalSessionId, apk = target)
                } else {
                    sl.installer.installApk(
                        apk = target,
                        firstInstall = !installedAlready,
                        referrerUri = android.net.Uri.parse(card.info.asset.browserDownloadUrl),
                    )
                }
                when (result) {
                    is InstallResult.Success -> {
                        if (pinned.isNullOrEmpty()) {
                            sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                        } else if (pinned != meta.signingSha256 && pinned in meta.lineageSha256) {
                            sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                            sl.logger.info(
                                "Install",
                                "Rolled pin forward for ${meta.applicationId}: $pinned -> ${meta.signingSha256}"
                            )
                        }
                        // Persist owner/repo → applicationId + installed tag so UpdateAvailable
                        // survives app restarts (discovery never returns applicationId).
                        sl.appIdCache.put(card.info.owner, card.info.repo, meta.applicationId, card.info.tagName)
                        sl.audit.installSucceeded(card.info, meta)
                        sl.logger.info("Install", "Installed ${meta.applicationId} ${meta.versionName}")
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
                                developerVerificationNotice = null, // clear on success
                            )
                        }
                    }
                    is InstallResult.Failure -> {
                        sl.audit.installFailed(card.info, meta, result.message)
                        sl.logger.warn("Install", "Install failed for ${meta.applicationId}: ${result.message}")
                        updateCard(card.info) { it.copy(status = CardStatus.Error, message = result.message) }
                    }
                }
            } catch (t: CancellationException) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
                throw t // Always rethrow so coroutine machinery works correctly.
            } catch (t: Throwable) {
                preapprovalSessionId?.let { sl.installer.abandonSession(it) }
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
        val cached = sl.appIdCache.get(card.info.owner, card.info.repo)
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

    fun uninstall(card: CardState) {
        val applicationId = card.info.applicationId ?: return
        sl.installer.openAppInfo(applicationId)
        sl.audit.uninstallInitiated(applicationId, card.info.handle)
        sl.logger.info("Uninstall", "Opened delete intent for $applicationId")
    }

    fun open(card: CardState) {
        val applicationId = card.info.applicationId ?: return
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

    private fun cardKey(info: AppInfo) = "${info.sourceKey}/${info.owner}/${info.repo}"

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
}
