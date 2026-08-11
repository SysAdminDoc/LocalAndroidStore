package com.sysadmin.lasstore.ui.catalog

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.install.QueuedUpdatePhase

internal data class AccessibilityAnnouncement(
    val text: String,
    val liveRegion: LiveRegionMode = LiveRegionMode.Polite,
)

/**
 * A small, non-focusable live region keeps asynchronous catalog state available to TalkBack.
 * The text is intentionally supplied through semantics instead of a visible duplicate label.
 */
@Composable
internal fun AccessibilityLiveRegion(
    announcement: AccessibilityAnnouncement?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(1.dp)
            .semantics {
                liveRegion = announcement?.liveRegion ?: LiveRegionMode.Polite
                contentDescription = announcement?.text.orEmpty()
            },
    )
}

@Composable
internal fun CatalogAccessibilityLiveRegion(state: CatalogUiState) {
    val announcement = when {
        state.warning != null -> AccessibilityAnnouncement(
            text = "Warning: ${state.warning}",
        )
        state.errorMessage != null -> AccessibilityAnnouncement(
            text = "Catalog refresh failed: ${state.errorMessage} Review the message, then refresh the catalog.",
        )
        state.catalogNotice != null -> AccessibilityAnnouncement(
            text = "Catalog refresh completed with a warning: ${state.catalogNotice}",
        )
        else -> null
    }
    AccessibilityLiveRegion(announcement)
}

@Composable
internal fun CardAccessibilityLiveRegion(state: CardState) {
    val current = cardAccessibilityAnnouncement(state)
    val currentKey = current?.let { "${it.liveRegion}:\u0000${it.text}" }
    var previousKey by remember(state.info.handle) { mutableStateOf<String?>(null) }
    var hasObservedState by remember(state.info.handle) { mutableStateOf(false) }
    var activeAnnouncement by remember(state.info.handle) {
        mutableStateOf<AccessibilityAnnouncement?>(null)
    }

    LaunchedEffect(currentKey) {
        if (hasObservedState && previousKey != currentKey) {
            activeAnnouncement = current
        }
        previousKey = currentKey
        hasObservedState = true
    }

    AccessibilityLiveRegion(activeAnnouncement)
}

internal fun cardAccessibilityAnnouncement(state: CardState): AccessibilityAnnouncement? {
    val appName = state.info.displayName
    state.queuedUpdateStatus?.let { queued ->
        return when (queued.phase) {
            QueuedUpdatePhase.Queued -> AccessibilityAnnouncement(
                text = "$appName: update queued for background installation. It will run when the device is ready.",
            )
            QueuedUpdatePhase.Running -> AccessibilityAnnouncement(
                text = "$appName: background update is running. Wait for verification and installation to finish.",
            )
            QueuedUpdatePhase.Retrying -> AccessibilityAnnouncement(
                text = "$appName: background update will retry. ${queued.message} Review the card for details.",
            )
            QueuedUpdatePhase.AuditPending -> AccessibilityAnnouncement(
                text = "$appName: update completed, but audit evidence is pending. Refresh after storage is available.",
            )
            QueuedUpdatePhase.AwaitingUserAction -> AccessibilityAnnouncement(
                text = "$appName: the background update needs your action. Open the card to continue the Android install.",
            )
            QueuedUpdatePhase.Installed -> AccessibilityAnnouncement(
                text = "$appName: background update installed successfully.",
            )
            QueuedUpdatePhase.Failed -> AccessibilityAnnouncement(
                text = "$appName: background update failed. ${queued.message} Review the error and retry.",
            )
            QueuedUpdatePhase.Cancelled -> AccessibilityAnnouncement(
                text = "$appName: background update cancelled. Queue it again when ready.",
            )
        }
    }

    return when (state.status) {
        CardStatus.Working -> state.message?.let { workingAnnouncement(appName, it) }
        CardStatus.PermissionReview -> AccessibilityAnnouncement(
            text = "$appName: permission review required. Review the requested permissions before continuing.",
        )
        CardStatus.Installed -> AccessibilityAnnouncement(
            text = "$appName: installation completed successfully.",
        )
        CardStatus.Archived -> AccessibilityAnnouncement(
            text = "$appName: archived. Its data is retained; choose Restore app to download it again.",
        )
        CardStatus.Error -> AccessibilityAnnouncement(
            text = "$appName: installation failed. ${state.message.orEmpty()} Review the error and retry.",
        )
        CardStatus.SignatureMismatch -> AccessibilityAnnouncement(
            text = "$appName: installation blocked because the publisher signature does not match. Review publisher trust before retrying.",
            liveRegion = LiveRegionMode.Assertive,
        )
        else -> null
    }
}

private fun workingAnnouncement(
    appName: String,
    rawMessage: String,
): AccessibilityAnnouncement {
    val stage = rawMessage.replace(Regex("\\s+\\d{1,3}%"), "").trim()
    val text = when {
        stage.contains("download", ignoreCase = true) ->
            "$appName: download in progress. Wait for verification before installation."
        stage.contains("pre-approval", ignoreCase = true) ||
            stage.contains("preapproval", ignoreCase = true) ->
            "$appName: waiting for Android install approval. Review the prompt to continue."
        stage.contains("install", ignoreCase = true) ->
            "$appName: installation in progress. Wait for Android to finish."
        stage.contains("inspect", ignoreCase = true) ||
            stage.contains("verif", ignoreCase = true) ->
            "$appName: verifying the downloaded APK. No action is needed yet."
        stage.contains("audit", ignoreCase = true) ->
            "$appName: recording install evidence. Wait for the audit to finish."
        else -> "$appName: $stage"
    }
    return AccessibilityAnnouncement(text)
}
