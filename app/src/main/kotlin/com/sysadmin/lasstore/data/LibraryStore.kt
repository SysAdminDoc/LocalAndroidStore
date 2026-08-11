package com.sysadmin.lasstore.data

import android.content.Context
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class LibraryCollection(
    val id: String,
    val name: String,
)

fun libraryKeysFor(
    applicationId: String?,
    sourceKey: String,
    owner: String,
    repo: String,
): List<String> = buildList {
    applicationId
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { add("package:${it.lowercase(Locale.US)}") }
    val sourceIdentity = listOf(sourceKey, owner, repo)
        .map(String::trim)
        .takeIf { it.all(String::isNotBlank) }
        ?.joinToString("/")
    sourceIdentity?.let { add("source:${it.lowercase(Locale.US)}") }
}.distinct()

/** Stores user-owned favorites and collection membership independently of catalog responses. */
class LibraryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun collections(): List<LibraryCollection> = readState().collections
        .map { LibraryCollection(it.id, it.name) }

    @Synchronized
    fun isFavorite(keys: Collection<String>): Boolean = matchingEntry(readState(), keys)
        ?.favorite == true

    @Synchronized
    fun collectionIds(keys: Collection<String>): Set<String> =
        matchingEntry(readState(), keys)?.collectionIds.orEmpty()

    @Synchronized
    fun toggleFavorite(keys: Collection<String>): Boolean {
        val validKeys = normalizeKeys(keys)
        if (validKeys.isEmpty()) return false
        val state = readState()
        val current = matchingEntry(state, validKeys)?.favorite == true
        val merged = mergeEntry(state, validKeys) { it.copy(favorite = !current) }
        writeState(merged)
        return !current
    }

    @Synchronized
    fun setCollections(keys: Collection<String>, collectionIds: Set<String>) {
        val validKeys = normalizeKeys(keys)
        if (validKeys.isEmpty()) return
        val state = readState()
        val validCollectionIds = state.collections.map { it.id }.toSet()
        val selected = collectionIds.intersect(validCollectionIds)
        writeState(
            mergeEntry(state, validKeys) { entry ->
                entry.copy(collectionIds = selected)
            },
        )
    }

    @Synchronized
    fun createCollection(rawName: String): LibraryCollection? {
        val name = normalizeCollectionName(rawName) ?: return null
        val state = readState()
        state.collections.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let {
            return LibraryCollection(it.id, it.name)
        }
        val collection = StoredCollection(UUID.randomUUID().toString(), name)
        writeState(state.copy(collections = state.collections + collection))
        return LibraryCollection(collection.id, collection.name)
    }

    @Synchronized
    fun renameCollection(id: String, rawName: String): LibraryCollection? {
        val name = normalizeCollectionName(rawName) ?: return null
        val state = readState()
        if (state.collections.any { it.id != id && it.name.equals(name, ignoreCase = true) }) {
            return null
        }
        val renamed = state.collections.map { collection ->
            if (collection.id == id) collection.copy(name = name) else collection
        }
        if (renamed == state.collections) return null
        writeState(state.copy(collections = renamed))
        return renamed.first { it.id == id }.let { LibraryCollection(it.id, it.name) }
    }

    @Synchronized
    fun deleteCollection(id: String) {
        val state = readState()
        if (state.collections.none { it.id == id }) return
        val entries = state.entries.mapNotNull { entry ->
            val updated = entry.copy(collectionIds = entry.collectionIds - id)
            updated.takeIf { it.favorite || it.collectionIds.isNotEmpty() }
        }
        writeState(
            state.copy(
                collections = state.collections.filterNot { it.id == id },
                entries = entries,
            ),
        )
    }

    /** Test and recovery hook; app updates never call this. */
    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear library state" }
    }

    private fun matchingEntry(state: StoredLibraryState, keys: Collection<String>): StoredEntry? {
        val validKeys = normalizeKeys(keys).toSet()
        if (validKeys.isEmpty()) return null
        val matches = state.entries.filter { it.key in validKeys }
        if (matches.isEmpty()) return null
        return StoredEntry(
            key = validKeys.first(),
            favorite = matches.any { it.favorite },
            collectionIds = matches.flatMap { it.collectionIds }.toSet(),
        )
    }

    private fun mergeEntry(
        state: StoredLibraryState,
        keys: Collection<String>,
        transform: (StoredEntry) -> StoredEntry,
    ): StoredLibraryState {
        val validKeys = normalizeKeys(keys)
        val merged = transform(
            matchingEntry(state, validKeys)
                ?: StoredEntry(key = validKeys.first()),
        ).copy(key = validKeys.first())
        val remaining = state.entries.filterNot { it.key in validKeys }
        val finalEntries = merged.takeIf { it.favorite || it.collectionIds.isNotEmpty() }
            ?.let { remaining + it }
            ?: remaining
        return state.copy(
            entries = finalEntries,
        )
    }

    private fun readState(): StoredLibraryState {
        val raw = preferences.getString(KEY_STATE, null) ?: return StoredLibraryState()
        return runCatching {
            json.decodeFromString<StoredLibraryState>(raw).sanitize()
        }.getOrElse { StoredLibraryState() }
    }

    private fun writeState(state: StoredLibraryState) {
        check(
            preferences.edit()
                .putString(KEY_STATE, json.encodeToString(state.sanitize()))
                .commit(),
        ) { "Could not persist library state" }
    }

    private fun normalizeKeys(keys: Collection<String>): List<String> = keys
        .map { it.trim().lowercase(Locale.US) }
        .filter { it.isNotBlank() && it.length <= MAX_KEY_LENGTH }
        .distinct()

    private fun normalizeCollectionName(rawName: String): String? = rawName
        .trim()
        .replace(Regex("\\s+"), " ")
        .take(MAX_COLLECTION_NAME_LENGTH)
        .takeIf { it.isNotBlank() }

    private fun StoredLibraryState.sanitize(): StoredLibraryState {
        val cleanCollections = collections
            .filter { it.id.isNotBlank() && it.name.isNotBlank() }
            .distinctBy(StoredCollection::id)
        val collectionIds = cleanCollections.map(StoredCollection::id).toSet()
        val cleanEntries = entries
            .map { entry ->
                entry.copy(
                    key = entry.key.trim().lowercase(Locale.US),
                    collectionIds = entry.collectionIds.intersect(collectionIds),
                )
            }
            .filter {
                it.key.isNotBlank() && it.key.length <= MAX_KEY_LENGTH &&
                    (it.favorite || it.collectionIds.isNotEmpty())
            }
            .groupBy(StoredEntry::key)
            .map { (key, entries) ->
                StoredEntry(
                    key = key,
                    favorite = entries.any(StoredEntry::favorite),
                    collectionIds = entries.flatMap(StoredEntry::collectionIds).toSet(),
                )
            }
        return StoredLibraryState(cleanCollections, cleanEntries)
    }

    private companion object {
        const val PREFERENCES_NAME = "las_library_v1"
        const val KEY_STATE = "state"
        const val MAX_COLLECTION_NAME_LENGTH = 48
        const val MAX_KEY_LENGTH = 240
    }
}

@Serializable
private data class StoredLibraryState(
    val collections: List<StoredCollection> = emptyList(),
    val entries: List<StoredEntry> = emptyList(),
)

@Serializable
private data class StoredCollection(
    val id: String,
    val name: String,
)

@Serializable
private data class StoredEntry(
    val key: String,
    val favorite: Boolean = false,
    val collectionIds: Set<String> = emptySet(),
)
