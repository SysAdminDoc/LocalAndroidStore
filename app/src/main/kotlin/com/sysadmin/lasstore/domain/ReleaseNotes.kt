package com.sysadmin.lasstore.domain

import kotlinx.serialization.Serializable

@Serializable
data class ReleaseNote(
    val versionName: String? = null,
    val versionCode: Long? = null,
    val label: String? = null,
    val body: String,
    val publishedAt: String? = null,
)

/** Validates the optional F-Droid whatsNew field before it can enter the catalog. */
fun validateWhatsNew(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    require(value.length <= MAX_WHATS_NEW_CHARS) {
        "F-Droid whatsNew exceeds the ${MAX_WHATS_NEW_CHARS}-character limit"
    }
    require(value.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
        "F-Droid whatsNew contains unsupported control characters"
    }
    return value
}

/** Returns bounded, newest-first notes newer than the installed release when possible. */
fun releaseNotesSinceInstalled(
    notes: List<ReleaseNote>,
    installedVersionCode: Long?,
    installedVersionName: String?,
    maxEntries: Int = MAX_CUMULATIVE_NOTE_ENTRIES,
): List<ReleaseNote> {
    val clean = notes
        .asSequence()
        .map { it.copy(body = it.body.trim()) }
        .filter { it.body.isNotBlank() }
        .distinctBy { releaseNoteIdentity(it) }
        .take(MAX_RELEASE_NOTE_ENTRIES)
        .toList()
    if (clean.isEmpty()) return emptyList()

    val installedIndex = installedVersionCode?.let { code ->
        clean.indexOfFirst { it.versionCode == code }.takeIf { it >= 0 }
    } ?: installedVersionName
        ?.let(::normalizeVersionLabel)
        ?.let { name ->
            clean.indexOfFirst { note ->
                normalizeVersionLabel(note.versionName ?: note.label) == name
            }.takeIf { it >= 0 }
        }

    val newer = when {
        installedIndex != null -> clean.take(installedIndex)
        installedVersionCode != null -> clean.filter { note ->
            note.versionCode?.let { it > installedVersionCode } == true
        }
        else -> clean
    }
    return newer.take(maxEntries.coerceIn(1, MAX_CUMULATIVE_NOTE_ENTRIES))
}

fun releaseNoteIdentity(note: ReleaseNote): String =
    note.versionCode?.toString()
        ?: normalizeVersionLabel(note.versionName ?: note.label)
        ?: note.body

private fun normalizeVersionLabel(value: String?): String? = value
    ?.trim()
    ?.removePrefix("v")
    ?.removePrefix("V")
    ?.takeIf { it.isNotBlank() }

private const val MAX_RELEASE_NOTE_ENTRIES = 64
private const val MAX_WHATS_NEW_CHARS = 16 * 1024
const val MAX_CUMULATIVE_NOTE_ENTRIES = 12
