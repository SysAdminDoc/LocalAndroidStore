package com.sysadmin.lasstore.domain

import java.util.Locale

data class AggregatedCatalogApp(
    val primary: AppInfo,
    val candidates: List<AppInfo>,
)

/** Groups only confirmed package identities; source-native cards without an identity stay distinct. */
fun aggregateCatalogApps(
    infos: List<AppInfo>,
    preferredSourceFor: (String) -> String? = { null },
    candidateAllowed: (AppInfo) -> Boolean = { true },
): List<AggregatedCatalogApp> {
    val groups = infos
        .filter { !it.applicationId.isNullOrBlank() }
        .groupBy { it.applicationId!!.trim().lowercase(Locale.US) }
    val emitted = mutableSetOf<String>()
    return buildList {
        infos.forEach { info ->
            val applicationId = info.applicationId?.trim()
            if (applicationId.isNullOrBlank()) {
                add(AggregatedCatalogApp(primary = info, candidates = listOf(info)))
                return@forEach
            }
            val identity = applicationId.lowercase(Locale.US)
            if (!emitted.add(identity)) return@forEach
            val candidates = groups.getValue(identity)
            val compatible = candidates.filter(candidateAllowed)
            val preferred = candidates.firstOrNull {
                it.sourceKey == preferredSourceFor(applicationId)
            }
            add(
                AggregatedCatalogApp(
                    primary = preferred
                        ?.takeIf(candidateAllowed)
                        ?: compatible.firstOrNull()
                        ?: preferred
                        ?: candidates.first(),
                    candidates = candidates,
                ),
            )
        }
    }
}
