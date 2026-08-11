package com.sysadmin.lasstore.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.AccentColor
import com.sysadmin.lasstore.data.AppThemeMode
import com.sysadmin.lasstore.data.FdroidSource
import com.sysadmin.lasstore.data.GitHubConnectionResult
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.MalformedSourceRegistryException
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.SourceDirectoryEntry
import com.sysadmin.lasstore.data.normalizeSources
import com.sysadmin.lasstore.data.sourceKey
import com.sysadmin.lasstore.data.validateSources
import com.sysadmin.lasstore.data.normalizeFdroidSources
import com.sysadmin.lasstore.data.validateFdroidSources
import com.sysadmin.lasstore.data.validateSourceDirectoryUrl
import com.sysadmin.lasstore.install.ExternalLaunchResult
import com.sysadmin.lasstore.install.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SettingsSaveStatus {
    Idle,
    Saving,
    Saved,
    Error,
}

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sourcePats: Map<String, String> = emptyMap(),
    val encryptedAtRest: Boolean = true,
    val saveError: String? = null,
    val saveStatus: SettingsSaveStatus = SettingsSaveStatus.Idle,
    val registryRecoveryRequired: Boolean = false,
    val registryRecoveryBackupAvailable: Boolean = false,
    val connectionChecks: Map<String, ConnectionCheckState> = emptyMap(),
    val shizukuSilentInstallEnabled: Boolean = false,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.Unavailable,
    val libraryExportBusy: Boolean = false,
    val libraryExportFile: File? = null,
    val libraryImportBusy: Boolean = false,
    val libraryMessage: String? = null,
    val libraryError: String? = null,
    val pendingLibraryRestoreCount: Int = 0,
    val sourceDirectoryEntries: List<SourceDirectoryEntry> = emptyList(),
    val sourceDirectoryAddedKeys: Set<String> = emptySet(),
    val sourceDirectoryBusy: Boolean = false,
    val sourceDirectoryError: String? = null,
    val sourceDirectoryMessage: String? = null,
) {
    val saving: Boolean get() = saveStatus == SettingsSaveStatus.Saving
}

data class ConnectionCheckState(
    val user: String,
    val running: Boolean = false,
    val result: GitHubConnectionResult? = null,
    val error: String? = null,
    val requiredScopes: Set<String> = emptySet(),
    val rateLimitRemaining: Long? = null,
    val rateLimitResetEpochMillis: Long? = null,
)

class SettingsViewModel : ViewModel() {
    private val sl = ServiceLocator
    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var saveJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sl.settings.recoverPendingTransaction()
                val inspection = sl.settings.inspectSourceRegistry()
                _state.update {
                    it.copy(
                        settings = inspection.settings,
                        sourcePats = inspection.settings.sources.associate { source ->
                            source.key to sl.settings.getSourcePat(source.key).orEmpty()
                        },
                        encryptedAtRest = sl.secrets.encrypted,
                        registryRecoveryRequired = inspection.requiresRecovery,
                        registryRecoveryBackupAvailable = inspection.backupAvailable,
                        shizukuSilentInstallEnabled = sl.installer.shizukuSilentInstallEnabled(),
                        shizukuStatus = sl.installer.shizukuStatus(),
                        pendingLibraryRestoreCount = sl.libraryRestore.pending().size,
                        sourceDirectoryAddedKeys = configuredSourceKeys(inspection.settings),
                    )
                }
                sl.settings.flow.collect { current ->
                    _state.update {
                        it.copy(
                            settings = current,
                            sourcePats = current.sources.associate { source ->
                                source.key to sl.settings.getSourcePat(source.key).orEmpty()
                            },
                            encryptedAtRest = sl.secrets.encrypted,
                            sourceDirectoryAddedKeys = configuredSourceKeys(current),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        saveStatus = SettingsSaveStatus.Error,
                        saveError = throwable.message
                            ?: "Could not recover the source registry. Retry Save settings.",
                    )
                }
            }
        }
    }

    fun save(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
    ) {
        save(
            sources = sources,
            sourcePats = sourcePats,
            fdroidSources = _state.value.settings.fdroidSources,
        )
    }

    fun save(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
        fdroidSources: List<FdroidSource>,
        hideUnverifiedSources: Boolean = _state.value.settings.hideUnverifiedSources,
        themeMode: AppThemeMode = _state.value.settings.themeMode,
        accentColor: AccentColor = _state.value.settings.accentColor,
        dynamicColor: Boolean = _state.value.settings.dynamicColor,
        dailyUpdateCap: Int = _state.value.settings.dailyUpdateCap,
    ) {
        saveInternal(
            sources = sources,
            sourcePats = sourcePats,
            fdroidSources = fdroidSources,
            hideUnverifiedSources = hideUnverifiedSources,
            themeMode = themeMode,
            accentColor = accentColor,
            dynamicColor = dynamicColor,
            dailyUpdateCap = dailyUpdateCap,
            replaceMalformedRegistry = false,
        )
    }

    fun replaceMalformedRegistry(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
    ) {
        replaceMalformedRegistry(
            sources = sources,
            sourcePats = sourcePats,
            fdroidSources = _state.value.settings.fdroidSources,
        )
    }

    fun replaceMalformedRegistry(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
        fdroidSources: List<FdroidSource>,
        hideUnverifiedSources: Boolean = _state.value.settings.hideUnverifiedSources,
        themeMode: AppThemeMode = _state.value.settings.themeMode,
        accentColor: AccentColor = _state.value.settings.accentColor,
        dynamicColor: Boolean = _state.value.settings.dynamicColor,
        dailyUpdateCap: Int = _state.value.settings.dailyUpdateCap,
    ) {
        saveInternal(
            sources = sources,
            sourcePats = sourcePats,
            fdroidSources = fdroidSources,
            hideUnverifiedSources = hideUnverifiedSources,
            themeMode = themeMode,
            accentColor = accentColor,
            dynamicColor = dynamicColor,
            dailyUpdateCap = dailyUpdateCap,
            replaceMalformedRegistry = true,
        )
    }

    private fun saveInternal(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
        fdroidSources: List<FdroidSource>,
        hideUnverifiedSources: Boolean,
        themeMode: AppThemeMode,
        accentColor: AccentColor,
        dynamicColor: Boolean,
        dailyUpdateCap: Int,
        replaceMalformedRegistry: Boolean,
    ) {
        if (_state.value.saveStatus == SettingsSaveStatus.Saving) return
        validateSources(sources)?.let { error ->
            _state.update {
                it.copy(
                    saveStatus = SettingsSaveStatus.Error,
                    saveError = error,
                )
            }
            return
        }
        validateFdroidSources(fdroidSources)?.let { error ->
            _state.update {
                it.copy(
                    saveStatus = SettingsSaveStatus.Error,
                    saveError = error,
                )
            }
            return
        }
        if (_state.value.registryRecoveryRequired && !replaceMalformedRegistry) {
            _state.update {
                it.copy(
                    saveStatus = SettingsSaveStatus.Error,
                    saveError = "The saved source registry needs recovery. Review the fallback entries and replace it intentionally.",
                )
            }
            return
        }
        val normalized = sources.map { source ->
            source.copy(
                user = source.user.trim(),
                topic = source.topic.trim(),
            )
        }
        val normalizedFdroidSources = normalizeFdroidSources(fdroidSources)
        _state.update {
            it.copy(
                saveStatus = SettingsSaveStatus.Saving,
                saveError = null,
            )
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val affectedSourceKeys = (
                    _state.value.settings.sources.map { it.key } + normalized.map { it.key } +
                        _state.value.settings.fdroidSources.map { it.key } +
                        normalizedFdroidSources.map { it.key }
                    ).toSet()
                val targetSettings = AppSettings(
                    sources = normalizeSources(normalized),
                    fdroidSources = normalizedFdroidSources,
                    hideUnverifiedSources = hideUnverifiedSources,
                    themeMode = themeMode,
                    accentColor = accentColor,
                    dynamicColor = dynamicColor,
                    dailyUpdateCap = dailyUpdateCap,
                    sourceDirectoryUrl = _state.value.settings.sourceDirectoryUrl,
                )
                val persistedSourcePats = sourcePats
                    .filter { (key, value) ->
                        key in targetSettings.sources.map { it.key } && value.isNotBlank()
                    }
                    .mapValues { (_, value) -> value.trim() }
                if (replaceMalformedRegistry) {
                    sl.settings.replaceMalformedSourceRegistry(
                        settings = targetSettings,
                        sourcePats = persistedSourcePats,
                    )
                } else {
                    sl.settings.saveSourceRegistry(
                        settings = targetSettings,
                        sourcePats = persistedSourcePats,
                    )
                }
                affectedSourceKeys.forEach { sourceKey ->
                    runCatching { sl.github.purgeSourceCache(sourceKey) }
                        .onFailure {
                            sl.logger.warn(
                                "Settings",
                                "Could not purge HTTP cache for $sourceKey: ${it.message}",
                            )
                        }
                    runCatching { sl.catalogSnapshots.purge(sourceKey) }
                        .onFailure {
                            sl.logger.warn(
                                "Settings",
                                "Could not purge snapshot for $sourceKey: ${it.message}",
                            )
                        }
                }
                val enabled = normalized.count { it.enabled }
                sl.logger.info(
                    "Settings",
                    "Saved ${normalized.size} GitHub sources ($enabled enabled) and " +
                        "${normalizedFdroidSources.size} F-Droid repositories",
                )
                _state.update {
                    it.copy(
                        settings = targetSettings,
                        sourcePats = persistedSourcePats,
                        saveStatus = SettingsSaveStatus.Saved,
                        saveError = null,
                        registryRecoveryRequired = false,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                sl.logger.error("Settings", "Could not save source registry", throwable)
                _state.update {
                    val malformed = throwable as? MalformedSourceRegistryException
                    it.copy(
                        saveStatus = SettingsSaveStatus.Error,
                        saveError = throwable.message ?: "Could not save the source registry.",
                        registryRecoveryRequired = it.registryRecoveryRequired || malformed != null,
                        registryRecoveryBackupAvailable =
                            it.registryRecoveryBackupAvailable || malformed?.backupAvailable == true,
                    )
                }
            }
        }
        saveJob = job
        job.invokeOnCompletion {
            if (saveJob == job) saveJob = null
        }
    }

    fun testConnection(user: String, pat: String) {
        val normalizedUser = user.trim()
        val key = sourceKey(normalizedUser)
        _state.update {
            it.copy(
                connectionChecks = it.connectionChecks + (
                    key to ConnectionCheckState(user = normalizedUser, running = true)
                    ),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = sl.github.testConnection(
                    user = normalizedUser,
                    patOverride = pat.trim().takeIf { it.isNotEmpty() },
                )
                _state.update {
                    it.copy(
                        connectionChecks = it.connectionChecks + (
                            key to ConnectionCheckState(
                                user = normalizedUser,
                                result = result,
                                rateLimitRemaining = result.rateLimitRemaining,
                                rateLimitResetEpochMillis = result.rateLimitResetEpochMillis,
                            )
                            ),
                    )
                }
            } catch (throwable: GitHubRequestException) {
                val scopeHint = throwable.acceptedScopes
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString()
                    ?.let { " Required GitHub scopes: $it." }
                    .orEmpty()
                _state.update {
                    it.copy(
                        connectionChecks = it.connectionChecks + (
                            key to ConnectionCheckState(
                                user = normalizedUser,
                                error = (throwable.message ?: "GitHub connection failed") + scopeHint,
                                requiredScopes = throwable.acceptedScopes,
                                rateLimitRemaining = throwable.rateLimitRemaining,
                                rateLimitResetEpochMillis = throwable.rateLimitResetEpochMillis,
                            )
                            ),
                    )
                }
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        connectionChecks = it.connectionChecks + (
                            key to ConnectionCheckState(
                                user = normalizedUser,
                                error = throwable.message ?: "GitHub connection failed",
                            )
                            ),
                    )
                }
            }
        }
    }

    fun refreshShizuku() {
        _state.update {
            it.copy(
                shizukuSilentInstallEnabled = sl.installer.shizukuSilentInstallEnabled(),
                shizukuStatus = sl.installer.shizukuStatus(),
            )
        }
    }

    fun setShizukuSilentInstallEnabled(enabled: Boolean) {
        if (!sl.installer.setShizukuSilentInstallEnabled(enabled)) {
            _state.update {
                it.copy(saveError = "Could not persist the Shizuku install preference.")
            }
            return
        }
        refreshShizuku()
    }

    fun requestShizukuPermission() {
        sl.installer.requestShizukuPermission()
        refreshShizuku()
    }

    fun openShizukuManager() {
        when (val result: ExternalLaunchResult = sl.installer.openShizukuManager()) {
            is ExternalLaunchResult.Failed -> _state.update { it.copy(saveError = result.message) }
            ExternalLaunchResult.Started -> Unit
        }
    }

    fun exportLibrary() {
        if (_state.value.libraryExportBusy || _state.value.libraryImportBusy) return
        _state.update {
            it.copy(
                libraryExportBusy = true,
                libraryExportFile = null,
                libraryMessage = null,
                libraryError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val settings = sl.settings.flow.first()
                val file = sl.libraryExport.create(settings)
                withContext(Dispatchers.Main) {
                    _state.update {
                        it.copy(libraryExportBusy = false, libraryExportFile = file)
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        libraryExportBusy = false,
                        libraryError = throwable.message ?: "Could not export the library.",
                    )
                }
            }
        }
    }

    fun clearLibraryExportFile() {
        _state.update { it.copy(libraryExportFile = null) }
    }

    fun reportLibraryError(message: String) {
        _state.update { it.copy(libraryError = message) }
    }

    fun importLibrary(uri: Uri) {
        if (_state.value.libraryExportBusy || _state.value.libraryImportBusy) return
        _state.update {
            it.copy(
                libraryImportBusy = true,
                libraryMessage = null,
                libraryError = null,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val imported = sl.libraryExport.read(uri)
                sl.settings.mergeExportedSources(
                    githubSources = imported.sources.github,
                    fdroidSources = imported.sources.fdroid,
                )
                val merge = sl.library.merge(imported.library)
                sl.libraryRestore.replace(imported.installs)
                _state.update {
                    it.copy(
                        libraryImportBusy = false,
                        pendingLibraryRestoreCount = sl.libraryRestore.pending().size,
                        libraryMessage = "Imported ${merge.entriesMerged} library entries and " +
                            "${imported.installs.size} managed install(s). Open Catalog and choose Restore.",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        libraryImportBusy = false,
                        libraryError = throwable.message ?: "Could not import the library.",
                    )
                }
            }
        }
    }

    fun clearPendingLibraryRestore() {
        sl.libraryRestore.clear()
        _state.update {
            it.copy(
                pendingLibraryRestoreCount = 0,
                libraryMessage = "The pending restore plan was cleared.",
            )
        }
    }

    fun fetchSourceDirectory(url: String) {
        val normalizedUrl = url.trim()
        validateSourceDirectoryUrl(normalizedUrl)?.let { error ->
            _state.update {
                it.copy(sourceDirectoryError = error, sourceDirectoryMessage = null)
            }
            return
        }
        if (_state.value.sourceDirectoryBusy) return
        _state.update {
            it.copy(
                sourceDirectoryBusy = true,
                sourceDirectoryError = null,
                sourceDirectoryMessage = null,
                sourceDirectoryEntries = emptyList(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val feed = sl.sourceDirectory.fetch(normalizedUrl)
                val current = sl.settings.flow.first()
                if (current.sourceDirectoryUrl != normalizedUrl) {
                    sl.settings.update(current.copy(sourceDirectoryUrl = normalizedUrl))
                }
                _state.update {
                    it.copy(
                        sourceDirectoryBusy = false,
                        sourceDirectoryEntries = feed.sources,
                        sourceDirectoryAddedKeys = configuredSourceKeys(current),
                        sourceDirectoryMessage = "Loaded ${feed.sources.size} curated source(s). Select one to add it.",
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        sourceDirectoryBusy = false,
                        sourceDirectoryError = throwable.message ?: "Could not load the source directory.",
                    )
                }
            }
        }
    }

    fun addSourceDirectoryEntry(entry: SourceDirectoryEntry) {
        if (_state.value.sourceDirectoryBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val github = entry.github?.copy(enabled = true)
                val fdroid = entry.fdroid?.copy(enabled = true)
                check(github != null || fdroid != null) { "The source definition is empty." }
                val settings = sl.settings.mergeExportedSources(
                    githubSources = github?.let(::listOf).orEmpty(),
                    fdroidSources = fdroid?.let(::listOf).orEmpty(),
                )
                _state.update {
                    it.copy(
                        sourceDirectoryAddedKeys = configuredSourceKeys(settings),
                        sourceDirectoryMessage = "Added ${entry.name.trim()} to the source registry.",
                        sourceDirectoryError = null,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _state.update {
                    it.copy(
                        sourceDirectoryError = throwable.message
                            ?: "Could not add the curated source.",
                    )
                }
            }
        }
    }

    private fun configuredSourceKeys(settings: AppSettings): Set<String> =
        (settings.sources.map { it.key } + settings.fdroidSources.map { it.key }).toSet()
}
