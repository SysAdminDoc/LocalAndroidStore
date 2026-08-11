package com.sysadmin.lasstore.domain

import java.util.Locale

const val GITHUB_TOPIC_TAG_NAMESPACE = "github:topic"
const val FDROID_CATEGORY_TAG_NAMESPACE = "fdroid:category"

/** Converts a source-provided label into a stable, searchable namespace-qualified tag. */
fun namespacedCatalogTag(namespace: String, rawValue: String): String? {
    val value = rawValue
        .trim()
        .lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
    return value.takeIf { it.isNotBlank() }?.let { "$namespace:$it" }
}

fun githubTopicTag(topic: String): String? =
    namespacedCatalogTag(GITHUB_TOPIC_TAG_NAMESPACE, topic)

fun fdroidCategoryTag(category: String): String? =
    namespacedCatalogTag(FDROID_CATEGORY_TAG_NAMESPACE, category)

/** Keeps the source namespace visible so similarly named categories do not become ambiguous. */
fun catalogTagLabel(tag: String): String {
    val normalized = tag.trim()
    return when {
        normalized.startsWith("$GITHUB_TOPIC_TAG_NAMESPACE:") ->
            "GitHub · ${normalized.substringAfterLast(':').replace('-', ' ')}"
        normalized.startsWith("$FDROID_CATEGORY_TAG_NAMESPACE:") ->
            "F-Droid · ${normalized.substringAfterLast(':').replace('-', ' ')}"
        else -> normalized
    }
}
