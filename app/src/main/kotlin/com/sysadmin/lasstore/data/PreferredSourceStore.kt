package com.sysadmin.lasstore.data

import android.content.Context

/** User-owned source preference for one Android package identity. */
class PreferredSourceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(applicationId: String): String? = preferences.getString(key(applicationId), null)

    @Synchronized
    fun set(applicationId: String, sourceKey: String) {
        check(
            preferences.edit()
                .putString(key(applicationId), sourceKey)
                .commit(),
        ) { "Could not persist preferred source" }
    }

    private fun key(applicationId: String): String = "preferred:${applicationId.trim()}"

    private companion object {
        const val PREFERENCES_NAME = "preferred_sources_v1"
    }
}
