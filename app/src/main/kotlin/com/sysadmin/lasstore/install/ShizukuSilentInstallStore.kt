package com.sysadmin.lasstore.install

import android.content.Context

/**
 * Stores the opt-in independently of the DataStore settings transaction.
 *
 * Installer workers need a synchronous read before they can choose a
 * PackageInstaller identity. A small, committed preference also survives a
 * process restart without making a background worker wait for the settings
 * flow to initialize.
 */
class ShizukuSilentInstallStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean): Boolean = preferences.edit()
        .putBoolean(KEY_ENABLED, enabled)
        .commit()

    private companion object {
        const val PREFERENCES_NAME = "las_shizuku_install_v1"
        const val KEY_ENABLED = "silent_install_enabled"
    }
}
