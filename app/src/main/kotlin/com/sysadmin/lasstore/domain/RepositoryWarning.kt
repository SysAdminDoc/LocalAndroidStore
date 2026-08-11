package com.sysadmin.lasstore.domain

import java.time.Instant

enum class RepositoryWarningKind {
    Archived,
    Inactive,
}

data class RepositoryWarning(
    val kind: RepositoryWarningKind,
    val lastActivityAt: String? = null,
)

fun repositoryMaintenanceWarning(
    archived: Boolean,
    lastActivityAt: String?,
    nowEpochMillis: Long = System.currentTimeMillis(),
): RepositoryWarning? {
    if (archived) return RepositoryWarning(RepositoryWarningKind.Archived, lastActivityAt)
    val activity = lastActivityAt
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: return null
    return (nowEpochMillis - activity)
        .takeIf { it >= INACTIVE_REPOSITORY_MILLIS }
        ?.let { RepositoryWarning(RepositoryWarningKind.Inactive, lastActivityAt) }
}

private const val INACTIVE_REPOSITORY_MILLIS = 365L * 24L * 60L * 60L * 1000L
