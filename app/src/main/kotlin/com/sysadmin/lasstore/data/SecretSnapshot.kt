package com.sysadmin.lasstore.data

import kotlinx.serialization.Serializable

@Serializable
internal data class PendingSourcePatTransaction(
    val id: String,
    val targetSettingsFingerprint: String,
    val previousSourcePats: Map<String, String>,
    val targetSourcePats: Map<String, String>,
)

@Serializable
internal data class SecretSnapshot(
    val globalPat: String = "",
    val sourcePats: Map<String, String> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
    val updatedAtEpochMillis: Long = 0L,
    val pendingSourcePatTransaction: PendingSourcePatTransaction? = null,
) {
    val isEmpty: Boolean
        get() = globalPat.isBlank() &&
            sourcePats.isEmpty() &&
            pins.isEmpty() &&
            pendingSourcePatTransaction == null

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

    fun replaceSourcePats(
        sourcePats: Map<String, String>,
        activeSourceKeys: Set<String>,
    ): SecretSnapshot {
        val next = sourcePats
            .asSequence()
            .filter { (key, value) -> key in activeSourceKeys && value.isNotBlank() }
            .associate { (key, value) -> key to value.trim() }
        return copy(sourcePats = next, updatedAtEpochMillis = System.currentTimeMillis())
    }

    fun beginSourcePatTransaction(
        id: String,
        targetSettingsFingerprint: String,
        targetSourcePats: Map<String, String>,
    ): SecretSnapshot {
        check(pendingSourcePatTransaction == null) {
            "A source PAT transaction is already pending"
        }
        return copy(
            pendingSourcePatTransaction = PendingSourcePatTransaction(
                id = id,
                targetSettingsFingerprint = targetSettingsFingerprint,
                previousSourcePats = sourcePats,
                targetSourcePats = targetSourcePats,
            ),
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun applySourcePatTransaction(id: String): SecretSnapshot {
        val pending = requirePendingSourcePatTransaction(id)
        return copy(
            sourcePats = pending.targetSourcePats,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun completeSourcePatTransaction(id: String): SecretSnapshot {
        requirePendingSourcePatTransaction(id)
        return copy(
            pendingSourcePatTransaction = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    fun rollbackSourcePatTransaction(id: String): SecretSnapshot {
        val pending = requirePendingSourcePatTransaction(id)
        return copy(
            sourcePats = pending.previousSourcePats,
            pendingSourcePatTransaction = null,
            updatedAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun requirePendingSourcePatTransaction(id: String): PendingSourcePatTransaction =
        checkNotNull(pendingSourcePatTransaction) { "No source PAT transaction is pending" }
            .also { check(it.id == id) { "A different source PAT transaction is pending" } }

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
            pendingSourcePatTransaction = pendingSourcePatTransaction
                ?: fallback.pendingSourcePatTransaction,
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
