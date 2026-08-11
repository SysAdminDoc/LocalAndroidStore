package com.sysadmin.lasstore.install

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BatchUninstallEntry(
    val applicationId: String,
    val displayName: String,
    val handle: String,
)

/** Persists the one-at-a-time Android confirmation queue for a multi-select uninstall action. */
class BatchUninstallStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(BatchUninstallEntry.serializer())

    @Synchronized
    fun begin(entries: List<BatchUninstallEntry>) {
        check(preferences.edit()
            .putString(QUEUE_KEY, json.encodeToString(serializer, entries.take(MAX_ENTRIES)))
            .putBoolean(AWAITING_KEY, false)
            .commit()) { "Could not persist uninstall batch" }
    }

    @Synchronized
    fun entries(): List<BatchUninstallEntry> = preferences
        .getString(QUEUE_KEY, null)
        ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
        .orEmpty()

    @Synchronized
    fun peek(): BatchUninstallEntry? = entries().firstOrNull()

    @Synchronized
    fun remove(applicationId: String): Boolean {
        val current = entries()
        val remaining = current.filterNot { it.applicationId == applicationId }
        if (remaining.size == current.size) return false
        persist(remaining)
        return true
    }

    @Synchronized
    fun markAwaitingConfirmation(awaiting: Boolean) {
        check(preferences.edit().putBoolean(AWAITING_KEY, awaiting).commit()) {
            "Could not persist uninstall batch state"
        }
    }

    @Synchronized
    fun isAwaitingConfirmation(): Boolean = preferences.getBoolean(AWAITING_KEY, false)

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(QUEUE_KEY).remove(AWAITING_KEY).commit()) {
            "Could not clear uninstall batch"
        }
    }

    private fun persist(entries: List<BatchUninstallEntry>) {
        check(preferences.edit().putString(QUEUE_KEY, json.encodeToString(serializer, entries)).commit()) {
            "Could not persist uninstall batch"
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "las_batch_uninstall_v1"
        const val QUEUE_KEY = "entries"
        const val AWAITING_KEY = "awaiting_confirmation"
        const val MAX_ENTRIES = 50
    }
}
