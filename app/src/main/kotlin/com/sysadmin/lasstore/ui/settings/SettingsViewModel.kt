package com.sysadmin.lasstore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.ServiceLocator
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
                        sourcePats = current.sources.associate { source -> source.key to sl.settings.getPat(source.key) },
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
        viewModelScope.launch(Dispatchers.IO) {
            val updated = AppSettings(sources = sources)
            sl.settings.update(updated)
            sources.forEach { source ->
                sl.settings.setPat(source.key, sourcePats[source.key].orEmpty())
            }
            val enabled = sources.count { it.enabled }
            sl.logger.info("Settings", "Saved ${sources.size} GitHub sources ($enabled enabled)")
            _state.update { it.copy(savedAt = System.currentTimeMillis(), sourcePats = sourcePats) }
        }
    }
}
