package com.sysadmin.lasstore.install

import android.content.Context

data class PendingArchiveRestore(
    val packageName: String,
    val unarchiveId: Int,
)

/** Durable hand-off from Android's archive broadcast to the foreground install pipeline. */
class ArchiveRestoreStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun pending(): PendingArchiveRestore? {
        val packageName = preferences.getString(KEY_PACKAGE, null) ?: return null
        val unarchiveId = preferences.getInt(KEY_ID, INVALID_ID)
        if (!PACKAGE_NAME_PATTERN.matches(packageName) || unarchiveId < 0) {
            clear()
            return null
        }
        return PendingArchiveRestore(packageName, unarchiveId)
    }

    @Synchronized
    fun set(packageName: String, unarchiveId: Int) {
        require(PACKAGE_NAME_PATTERN.matches(packageName)) { "Invalid archived package name" }
        require(unarchiveId >= 0) { "Invalid unarchive id" }
        check(
            preferences.edit()
                .putString(KEY_PACKAGE, packageName)
                .putInt(KEY_ID, unarchiveId)
                .commit(),
        ) { "Could not persist the pending archive restore" }
    }

    @Synchronized
    fun clearIf(packageName: String, unarchiveId: Int) {
        val current = pending()
        if (current?.packageName == packageName && current.unarchiveId == unarchiveId) clear()
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(KEY_PACKAGE).remove(KEY_ID).apply()
    }

    private companion object {
        const val PREFERENCES = "las_archive_restore_v1"
        const val KEY_PACKAGE = "package"
        const val KEY_ID = "unarchive_id"
        const val INVALID_ID = -1
        val PACKAGE_NAME_PATTERN = Regex(
            "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$",
        )
    }
}
