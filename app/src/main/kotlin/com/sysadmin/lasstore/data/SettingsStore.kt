package com.sysadmin.lasstore.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val githubUser: String = "SysAdminDoc",
    val topic: String = "android-app",
    val filterByTopic: Boolean = false,
    val showPrereleases: Boolean = false,
)

class SettingsStore(private val context: Context, private val secrets: SecretStore) {
    private val keyUser = stringPreferencesKey("github_user")
    private val keyTopic = stringPreferencesKey("topic")
    private val keyFilterByTopic = booleanPreferencesKey("filter_by_topic")
    private val keyPrereleases = booleanPreferencesKey("show_prereleases")

    val flow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            githubUser = prefs[keyUser] ?: "SysAdminDoc",
            topic = prefs[keyTopic] ?: "android-app",
            filterByTopic = prefs[keyFilterByTopic] ?: false,
            showPrereleases = prefs[keyPrereleases] ?: false,
        )
    }

    suspend fun update(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[keyUser] = settings.githubUser.trim()
            prefs[keyTopic] = settings.topic.trim().ifBlank { "android-app" }
            prefs[keyFilterByTopic] = settings.filterByTopic
            prefs[keyPrereleases] = settings.showPrereleases
        }
    }

    fun getPat(): String = secrets.getPat()
    fun setPat(pat: String) = secrets.setPat(pat)
}
