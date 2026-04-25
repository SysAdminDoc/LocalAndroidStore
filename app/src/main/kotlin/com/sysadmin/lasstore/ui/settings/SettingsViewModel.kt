package com.sysadmin.lasstore.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val pat: String = "",
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
                        pat = sl.settings.getPat(),
                        encryptedAtRest = sl.secrets.encrypted,
                    )
                }
            }
        }
    }

    fun save(
        user: String,
        topic: String,
        filterByTopic: Boolean,
        showPrereleases: Boolean,
        pat: String,
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            sl.settings.update(
                AppSettings(
                    githubUser = user,
                    topic = topic,
                    filterByTopic = filterByTopic,
                    showPrereleases = showPrereleases,
                )
            )
            sl.settings.setPat(pat)
            sl.logger.info("Settings", "Saved settings for user=$user filter=$filterByTopic prereleases=$showPrereleases")
            _state.update { it.copy(savedAt = System.currentTimeMillis(), pat = pat) }
        }
    }
}
