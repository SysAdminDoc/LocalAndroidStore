package com.sysadmin.lasstore.ui.catalog

import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppIdEntry
import com.sysadmin.lasstore.data.ApkMetadata
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
    /** New dangerous permissions the update requests vs the installed version (Item 34). */
    val newDangerousPermissions: List<String> = emptyList(),
    /** True when the user has silenced update notifications for this app (Item 35). */
    val isIgnored: Boolean = false,
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
        val isIgnored = sl.ignoreList.isIgnored(info.handle)
        return when {
            installed == null -> CardState(info = info, status = CardStatus.NotInstalled)
            else -> {
                // Compare latest release tag against the tag that was installed.
                // We can't compare versionCodes without downloading the new APK, so tagName
                // is the reliable proxy (GitHub release tags change with every new release).
                val updateAvailable = cached != null && info.tagName != cached.installedTagName
                // Ignored repos show as Installed even when an update is available (Item 35).
                val status = when {
                    updateAvailable && !isIgnored -> CardStatus.UpdateAvailable
                    else -> CardStatus.Installed
                }
                CardState(
                    info = info.copy(applicationId = applicationId),
                    status = status,
                    installedVersion = installed.versionName,
                    installedVersionCode = installed.versionCode,
                    isIgnored = isIgnored,
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

                // Item 34: Pause for permission review when an update requests new dangerous perms.
                val referrerUri = android.net.Uri.parse(card.info.asset.browserDownloadUrl)
                if (installedAlready) {
                    val newDangerousPerms = computeNewDangerousPermissions(meta)
                    if (newDangerousPerms.isNotEmpty()) {
                        pendingInstalls[key] = PendingInstallData(target, meta, pinned, installedAlready, preapprovalSessionId, referrerUri)
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
        resetCard(card)
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
                throw t
            } catch (t: Throwable) {
                pending.preapprovalSessionId?.let { sl.installer.abandonSession(it) }
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
        resetCard(card)
    }

    /** Item 35: Toggle update-ignore for this app. Rebuilds the card to reflect the new state. */
    fun toggleIgnore(card: CardState) {
        sl.ignoreList.toggle(card.info.handle)
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

    /** Item 62: Download the APK and save it to the Downloads folder without installing. */
    fun saveApk(card: CardState) {
        val key = cardKey(card.info)
        activeJobs[key]?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            updateCard(card.info) { it.copy(status = CardStatus.Working, progress = 0.01f, message = "Downloading…") }
            try {
                val safeTag = card.info.tagName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val filename = "${card.info.displayName}_${safeTag}.apk"
                    .replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val cacheDir = File(sl.appContext.cacheDir, "apks").apply { mkdirs() }
                val target = File(cacheDir, filename)
                sl.github.download(
                    url = card.info.asset.browserDownloadUrl,
                    target = target,
                    patOverride = sl.settings.getPat(card.info.sourceKey),
                ) { d, t ->
                    val frac = if (t > 0) (d.toFloat() / t.toFloat()).coerceIn(0f, 1f) else 0f
                    updateCard(card.info) { it.copy(progress = frac, message = "Downloading… ${(frac * 100).toInt()}%") }
                }
                saveToDownloads(filename, target)
                target.delete()
                val cached = sl.appIdCache.get(card.info.owner, card.info.repo)
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

    private fun resetCard(card: CardState) {
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
        val developerVerificationNotice = sl.developerVerification.evaluate(meta)
        if (developerVerificationNotice != null) {
            sl.logger.warn(
                "DeveloperVerification",
                "Preflight warning for ${meta.applicationId}: ${developerVerificationNotice.reason}"
            )
            sl.audit.developerVerificationWarned(info = card.info, meta = meta, reason = developerVerificationNotice.reason)
            updateCard(card.info) { it.copy(developerVerificationNotice = developerVerificationNotice) }
        }

        updateCard(card.info) { it.copy(message = "Installing…") }
        val result = if (preapprovalSessionId != null) {
            sl.installer.commitSession(sessionId = preapprovalSessionId, apk = target)
        } else {
            sl.installer.installApk(apk = target, firstInstall = !installedAlready, referrerUri = referrerUri)
        }
        when (result) {
            is InstallResult.Success -> {
                if (pinned.isNullOrEmpty()) {
                    sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                } else if (pinned != meta.signingSha256 && pinned in meta.lineageSha256) {
                    sl.secrets.setPin(meta.applicationId, meta.signingSha256)
                    sl.logger.info("Install", "Rolled pin forward for ${meta.applicationId}: $pinned -> ${meta.signingSha256}")
                }
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
                        developerVerificationNotice = null,
                        newDangerousPermissions = emptyList(),
                    )
                }
            }
            is InstallResult.Failure -> {
                sl.audit.installFailed(card.info, meta, result.message)
                sl.logger.warn("Install", "Install failed for ${meta.applicationId}: ${result.message}")
                updateCard(card.info) { it.copy(status = CardStatus.Error, message = result.message) }
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

        return meta.requestedPermissions.filter { perm ->
            perm !in installedPerms && isDangerous(perm)
        }
    }

    private fun isDangerous(permission: String): Boolean = runCatching {
        val info = sl.appContext.packageManager.getPermissionInfo(permission, 0)
        (info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) ==
            android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

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
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
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
