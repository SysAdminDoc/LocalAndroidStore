package com.sysadmin.lasstore.data

import android.content.Context

/**
 * Persists a set of repo handles (owner/repo) whose update notifications are suppressed.
 * When a handle is ignored, UpdateAvailable is displayed as Installed in the catalog.
 *
 * Backed by SharedPreferences (each handle stored as a boolean key). Reads are safe from
 * background threads; writes are async via apply().
 */
class IgnoreListStore(context: Context) {
    private val prefs = context.getSharedPreferences("las_ignore_list", Context.MODE_PRIVATE)

    fun isIgnored(handle: String): Boolean = prefs.getBoolean(handle, false)

    fun setIgnored(handle: String, ignored: Boolean) {
        prefs.edit().putBoolean(handle, ignored).apply()
    }

    fun toggle(handle: String) = setIgnored(handle, !isIgnored(handle))
}
