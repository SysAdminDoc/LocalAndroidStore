package com.sysadmin.lasstore.install

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Durable staging queue for a user-confirmed batch of background update actions. */
class DownloadQueueStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val serializer = ListSerializer(QueuedUpdatePayload.serializer())

    @Synchronized
    fun payloads(): List<QueuedUpdatePayload> = read()

    @Synchronized
    fun stage(payload: QueuedUpdatePayload): Boolean {
        val current = read().toMutableList()
        val existingIndex = current.indexOfFirst { it.workName == payload.workName }
        if (existingIndex >= 0) {
            if (current[existingIndex] == payload) return false
            current[existingIndex] = payload
        } else {
            if (current.size >= MAX_ENTRIES) {
                throw IllegalStateException("The staged update queue is full")
            }
            current += payload
        }
        persist(current)
        return true
    }

    @Synchronized
    fun remove(workName: String): Boolean {
        val current = read()
        val remaining = current.filterNot { it.workName == workName }
        if (remaining.size == current.size) return false
        persist(remaining)
        return true
    }

    @Synchronized
    fun clear() {
        persist(emptyList())
    }

    private fun read(): List<QueuedUpdatePayload> = preferences
        .getString(QUEUE_KEY, null)
        ?.let { raw -> runCatching { json.decodeFromString(serializer, raw) }.getOrNull() }
        .orEmpty()

    private fun persist(payloads: List<QueuedUpdatePayload>) {
        check(
            preferences.edit()
                .putString(QUEUE_KEY, json.encodeToString(serializer, payloads))
                .commit(),
        ) { "Could not persist staged update queue" }
    }

    private companion object {
        const val PREFERENCES_NAME = "las_download_queue_v1"
        const val QUEUE_KEY = "staged_payloads"
        const val MAX_ENTRIES = 100
    }
}
