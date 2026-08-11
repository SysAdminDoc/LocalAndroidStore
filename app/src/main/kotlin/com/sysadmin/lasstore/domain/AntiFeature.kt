package com.sysadmin.lasstore.domain

/** Visual severity used for the compact anti-feature badges on catalog cards. */
enum class AntiFeatureSeverity {
    Warning,
    Danger,
}

data class AntiFeatureBadge(
    val key: String,
    val label: String,
    val severity: AntiFeatureSeverity,
)

/**
 * Converts F-Droid's stable anti-feature keys into concise labels and a useful
 * visual severity. Unknown keys remain visible as warnings so a newer index
 * cannot silently hide metadata the client does not know yet.
 */
fun antiFeatureBadge(raw: String): AntiFeatureBadge? {
    val value = raw.trim()
    if (value.isEmpty()) return null

    return when (value.compactAntiFeatureKey()) {
        "ads" -> AntiFeatureBadge("Ads", "Ads", AntiFeatureSeverity.Warning)
        "tracking" -> AntiFeatureBadge("Tracking", "Tracking", AntiFeatureSeverity.Danger)
        "nonfreenet", "nonfreenetwork" -> AntiFeatureBadge(
            key = "NonFreeNet",
            label = "Non-free network",
            severity = AntiFeatureSeverity.Warning,
        )
        "nonfreeadd", "nonfreeaddon", "nonfreeaddons" -> AntiFeatureBadge(
            key = "NonFreeAdd",
            label = "Non-free add-ons",
            severity = AntiFeatureSeverity.Warning,
        )
        "nonfreedep", "nonfreedependency", "nonfreedependencies" -> AntiFeatureBadge(
            key = "NonFreeDep",
            label = "Non-free dependencies",
            severity = AntiFeatureSeverity.Warning,
        )
        "nonfreeassets", "nonfreeasset" -> AntiFeatureBadge(
            key = "NonFreeAssets",
            label = "Non-free assets",
            severity = AntiFeatureSeverity.Warning,
        )
        "upstreamnonfree" -> AntiFeatureBadge(
            key = "UpstreamNonFree",
            label = "Non-free upstream",
            severity = AntiFeatureSeverity.Warning,
        )
        "nosourcesince" -> AntiFeatureBadge(
            key = "NoSourceSince",
            label = "No source since",
            severity = AntiFeatureSeverity.Warning,
        )
        "knownvuln", "knownvulnerability", "knownvulnerabilities" -> AntiFeatureBadge(
            key = "KnownVuln",
            label = "Known vulnerability",
            severity = AntiFeatureSeverity.Danger,
        )
        "disabledalgorithm", "disabledalgorithms" -> AntiFeatureBadge(
            key = "DisabledAlgorithm",
            label = "Disabled algorithm",
            severity = AntiFeatureSeverity.Danger,
        )
        "tetherednet", "tetherednetwork" -> AntiFeatureBadge(
            key = "TetheredNet",
            label = "Tethered network",
            severity = AntiFeatureSeverity.Warning,
        )
        else -> AntiFeatureBadge(
            key = value,
            label = value.humanizeAntiFeatureKey(),
            severity = AntiFeatureSeverity.Warning,
        )
    }
}

fun antiFeatureBadges(values: Iterable<String>): List<AntiFeatureBadge> = values
    .mapNotNull(::antiFeatureBadge)
    .distinctBy { it.key.lowercase() }
    .sortedWith(
        compareBy<AntiFeatureBadge> { antiFeatureOrder.indexOf(it.key).takeUnless { index -> index < 0 } ?: Int.MAX_VALUE }
            .thenBy { it.label.lowercase() },
    )

private val antiFeatureOrder = listOf(
    "Ads",
    "Tracking",
    "NonFreeNet",
    "NonFreeAdd",
    "NonFreeDep",
    "NonFreeAssets",
    "UpstreamNonFree",
    "NoSourceSince",
    "KnownVuln",
    "DisabledAlgorithm",
    "TetheredNet",
)

private fun String.compactAntiFeatureKey(): String = filter(Char::isLetterOrDigit).lowercase()

private fun String.humanizeAntiFeatureKey(): String = replace(
    Regex("([a-z])([A-Z])"),
    "$1 $2",
).replace('_', ' ')
    .replace('-', ' ')
    .trim()
    .split(Regex("\\s+"))
    .filter(String::isNotEmpty)
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
