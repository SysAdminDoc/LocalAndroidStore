package com.sysadmin.lasstore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.GitHubConnectionResult
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.MalformedSourceRegistryException
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.normalizeSources
import com.sysadmin.lasstore.data.sourceKey
import com.sysadmin.lasstore.data.validateSources
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
        saveInternal(sources, sourcePats, replaceMalformedRegistry = false)
    }

    fun replaceMalformedRegistry(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
    ) {
        saveInternal(sources, sourcePats, replaceMalformedRegistry = true)
    }

    private fun saveInternal(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
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
        _state.update {
            it.copy(
                saveStatus = SettingsSaveStatus.Saving,
                saveError = null,
            )
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val affectedSourceKeys = (
                    _state.value.settings.sources.map { it.key } + normalized.map { it.key }
                    ).toSet()
                val targetSettings = AppSettings(sources = normalizeSources(normalized))
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
                sl.logger.info("Settings", "Saved ${normalized.size} GitHub sources ($enabled enabled)")
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
}
