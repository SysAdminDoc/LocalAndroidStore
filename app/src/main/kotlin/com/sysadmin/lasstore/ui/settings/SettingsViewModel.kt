package com.sysadmin.lasstore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.GitHubConnectionResult
import com.sysadmin.lasstore.data.GitHubRequestException
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.sourceKey
import com.sysadmin.lasstore.data.validateSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val sourcePats: Map<String, String> = emptyMap(),
    val encryptedAtRest: Boolean = true,
    val savedAt: Long = 0L,
    val saveError: String? = null,
    val saving: Boolean = false,
    val connectionChecks: Map<String, ConnectionCheckState> = emptyMap(),
)

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

    init {
        viewModelScope.launch {
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
        }
    }

    fun save(
        sources: List<GitHubSource>,
        sourcePats: Map<String, String>,
    ) {
        validateSources(sources)?.let { error ->
            _state.update { it.copy(saveError = error, saving = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(saving = true, saveError = null) }
            try {
                val normalized = sources.map { source ->
                    source.copy(
                        user = source.user.trim(),
                        topic = source.topic.trim(),
                    )
                }
                val affectedSourceKeys = (
                    _state.value.settings.sources.map { it.key } + normalized.map { it.key }
                    ).toSet()
                affectedSourceKeys.forEach { sourceKey ->
                    sl.github.purgeSourceCache(sourceKey)
                    sl.catalogSnapshots.purge(sourceKey)
                }
                sl.settings.replaceSourcePats(
                    sourcePats = sourcePats,
                    activeSourceKeys = normalized.map { it.key }.toSet(),
                )
                sl.settings.update(AppSettings(sources = normalized))
                val enabled = normalized.count { it.enabled }
                sl.logger.info("Settings", "Saved ${normalized.size} GitHub sources ($enabled enabled)")
                _state.update {
                    it.copy(
                        savedAt = System.currentTimeMillis(),
                        sourcePats = sourcePats,
                        saving = false,
                        saveError = null,
                    )
                }
            } catch (throwable: Throwable) {
                sl.logger.error("Settings", "Could not save source registry", throwable)
                _state.update {
                    it.copy(
                        saving = false,
                        saveError = throwable.message ?: "Could not save the source registry.",
                    )
                }
            }
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
