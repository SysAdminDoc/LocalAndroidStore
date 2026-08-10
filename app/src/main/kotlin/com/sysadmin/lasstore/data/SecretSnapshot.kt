package com.sysadmin.lasstore.data

import kotlinx.serialization.Serializable

@Serializable
internal data class SecretSnapshot(
    val globalPat: String = "",
    val sourcePats: Map<String, String> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
    val updatedAtEpochMillis: Long = 0L,
) {
    val isEmpty: Boolean
        get() = globalPat.isBlank() && sourcePats.isEmpty() && pins.isEmpty()

    fun withGlobalPat(pat: String): SecretSnapshot =
        copy(globalPat = pat.trim(), updatedAtEpochMillis = System.currentTimeMillis())

    fun withSourcePat(sourceKey: String, pat: String): SecretSnapshot {
        val trimmed = pat.trim()
        val next = if (trimmed.isBlank()) {
            sourcePats - sourceKey
        } else {
            sourcePats + (sourceKey to trimmed)
        }
        return copy(sourcePats = next, updatedAtEpochMillis = System.currentTimeMillis())
    }

    fun withPin(packageName: String, sha256Hex: String): SecretSnapshot =
        copy(
            pins = pins + (packageName to sha256Hex),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )

    fun withoutPin(packageName: String): SecretSnapshot =
        copy(pins = pins - packageName, updatedAtEpochMillis = System.currentTimeMillis())

    fun mergedWithFallback(fallback: SecretSnapshot): SecretSnapshot =
        SecretSnapshot(
            globalPat = globalPat.ifBlank { fallback.globalPat },
            sourcePats = fallback.sourcePats + sourcePats,
            pins = fallback.pins + pins,
            updatedAtEpochMillis = maxOf(updatedAtEpochMillis, fallback.updatedAtEpochMillis),
        )

    /**
     * Merge the two backends during recovery. A fallback snapshot is created while Tink is
     * unavailable, so it wins when it is newer. Legacy snapshots have no generation timestamp;
     * in that case preserving the plaintext values is safer than silently discarding them.
     */
    fun mergeForMigration(fallback: SecretSnapshot): SecretSnapshot {
        if (fallback.isEmpty) return this
        if (fallback.updatedAtEpochMillis >= updatedAtEpochMillis) {
            return fallback.mergedWithFallback(this)
        }
        return mergedWithFallback(fallback)
    }
}
