package com.sysadmin.lasstore.data

import android.content.Context

/**
 * Lightweight non-secret cache mapping owner/repo → the applicationId and release tag
 * that was last successfully installed via LAS.
 *
 * Used to:
 *  - hydrate [AppInfo.applicationId] after a cold-start refresh (discovery always returns null)
 *  - detect "update available" by comparing the cached installedTagName with the latest tagName
 *  - supply the applicationId for [PackageInstallerService.requestPreapproval] on API 34+
 */
data class AppIdEntry(
    val applicationId: String,
    val installedTagName: String,
)

class AppIdCache(context: Context) {
    private val prefs = context.getSharedPreferences("las_appid_cache", Context.MODE_PRIVATE)

    fun get(owner: String, repo: String): AppIdEntry? {
        val applicationId = prefs.getString(appIdKey(owner, repo), null) ?: return null
        val tagName = prefs.getString(tagKey(owner, repo), null) ?: return null
        return AppIdEntry(applicationId, tagName)
    }

    fun put(owner: String, repo: String, applicationId: String, installedTagName: String) {
        prefs.edit()
            .putString(appIdKey(owner, repo), applicationId)
            .putString(tagKey(owner, repo), installedTagName)
            .apply()
    }

    private fun appIdKey(owner: String, repo: String) = "appid:$owner/$repo"
    private fun tagKey(owner: String, repo: String) = "tag:$owner/$repo"
}
