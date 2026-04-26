package com.sysadmin.lasstore.data

import kotlinx.serialization.Serializable

@Serializable
internal data class SecretSnapshot(
    val globalPat: String = "",
    val sourcePats: Map<String, String> = emptyMap(),
    val pins: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = globalPat.isBlank() && sourcePats.isEmpty() && pins.isEmpty()

    fun withGlobalPat(pat: String): SecretSnapshot = copy(globalPat = pat.trim())

    fun withSourcePat(sourceKey: String, pat: String): SecretSnapshot {
        val trimmed = pat.trim()
        val next = if (trimmed.isBlank()) {
            sourcePats - sourceKey
        } else {
            sourcePats + (sourceKey to trimmed)
        }
        return copy(sourcePats = next)
    }

    fun withPin(packageName: String, sha256Hex: String): SecretSnapshot =
        copy(pins = pins + (packageName to sha256Hex))

    fun withoutPin(packageName: String): SecretSnapshot =
        copy(pins = pins - packageName)

    fun mergedWithFallback(fallback: SecretSnapshot): SecretSnapshot =
        SecretSnapshot(
            globalPat = globalPat.ifBlank { fallback.globalPat },
            sourcePats = fallback.sourcePats + sourcePats,
            pins = fallback.pins + pins,
        )
}
