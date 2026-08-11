package com.sysadmin.lasstore.ui.settings

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
import com.sysadmin.lasstore.data.normalizeSources
import com.sysadmin.lasstore.data.sourceKey
import com.sysadmin.lasstore.data.validateSources
import com.sysadmin.lasstore.data.normalizeFdroidSources
import com.sysadmin.lasstore.data.validateFdroidSources
import com.sysadmin.lasstore.install.ExternalLaunchResult
import com.sysadmin.lasstore.install.ShizukuStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
}
