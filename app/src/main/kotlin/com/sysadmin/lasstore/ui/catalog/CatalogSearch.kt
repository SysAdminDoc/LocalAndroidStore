package com.sysadmin.lasstore.ui.catalog

import com.sysadmin.lasstore.domain.CardStatus
import java.util.Locale

private val separatorRegex = Regex("[^a-z0-9]+")
private val camelCaseBoundaryRegex = Regex("([a-z])([A-Z])")

fun filterCards(cards: List<CardState>, query: String): List<CardState> {
    val tokens = query.searchTokens()
    if (tokens.isEmpty()) return cards

    return cards
        .mapIndexedNotNull { index, card ->
            val fields = card.searchFields()
            val tokenScores = tokens.map { token ->
                fields.maxOfOrNull { field -> field.scoreToken(token) } ?: 0
            }
            if (tokenScores.any { it == 0 }) {
                null
            } else {
                ScoredCard(
                    card = card,
                    score = tokenScores.sum() + card.status.searchBoost(),
                    originalIndex = index,
                )
            }
        }
        .sortedWith(
            compareByDescending<ScoredCard> { it.score }
                .thenByDescending { it.card.info.stars }
                .thenBy { it.card.info.displayName.lowercase(Locale.US) }
                .thenBy { it.originalIndex }
        )
        .map { it.card }
}

private data class ScoredCard(
    val card: CardState,
    val score: Int,
    val originalIndex: Int,
)

private fun CardState.searchFields(): List<String> = listOfNotNull(
    info.displayName,
    info.repo,
    info.owner,
    info.sourceLabel,
    info.handle,
    info.description,
    info.tagName,
    info.versionName,
    info.applicationId,
).map { it.normalizedSearchText() }

private fun String.scoreToken(token: String): Int {
    if (isBlank()) return 0

    val compactField = replace(" ", "")
    val words = split(" ").filter { it.isNotBlank() }
    val acronym = words.joinToString("") { it.take(1) }

    return when {
        this == token -> 140
        compactField == token -> 135
        words.any { it == token } -> 120
        words.any { it.startsWith(token) } -> 105
        contains(token) -> 90
        acronym.startsWith(token) -> 78
        compactField.isOrderedSubsequenceOf(token) -> 55 + (token.length * 20 / compactField.length.coerceAtLeast(1))
        else -> 0
    }
}

private fun CardStatus.searchBoost(): Int = when (this) {
    CardStatus.UpdateAvailable -> 8
    CardStatus.Installed -> 5
    CardStatus.NotInstalled -> 2
    CardStatus.Working,
    CardStatus.Error,
    CardStatus.SignatureMismatch -> 0
}

private fun String.searchTokens(): List<String> =
    normalizedSearchText()
        .split(" ")
        .filter { it.isNotBlank() }

private fun String.normalizedSearchText(): String =
    replace(camelCaseBoundaryRegex, "$1 $2")
        .lowercase(Locale.US)
        .replace(separatorRegex, " ")
        .trim()

private fun String.isOrderedSubsequenceOf(token: String): Boolean {
    if (token.length < 2) return false
    var index = 0
    for (char in this) {
        if (index < token.length && char == token[index]) {
            index += 1
        }
        if (index == token.length) return true
    }
    return false
}
