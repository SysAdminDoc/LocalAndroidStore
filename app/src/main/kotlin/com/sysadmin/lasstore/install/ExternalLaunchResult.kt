package com.sysadmin.lasstore.install

import android.content.ActivityNotFoundException
import android.content.Intent

sealed interface ExternalLaunchResult {
    data object Started : ExternalLaunchResult
    data class Failed(val message: String) : ExternalLaunchResult
}

internal fun safeLaunchExternalIntent(
    intent: Intent,
    canResolve: (Intent) -> Boolean,
    start: (Intent) -> Unit,
    failureMessage: String,
): ExternalLaunchResult {
    val resolved = runCatching { canResolve(intent) }.getOrDefault(false)
    if (!resolved) {
        return ExternalLaunchResult.Failed(
            "$failureMessage No compatible Android activity is available.",
        )
    }
    return try {
        start(intent)
        ExternalLaunchResult.Started
    } catch (_: ActivityNotFoundException) {
        ExternalLaunchResult.Failed(
            "$failureMessage The Android activity disappeared; try again.",
        )
    } catch (_: SecurityException) {
        ExternalLaunchResult.Failed(
            "$failureMessage Android denied the request.",
        )
    } catch (_: IllegalArgumentException) {
        ExternalLaunchResult.Failed(
            "$failureMessage The request was invalid.",
        )
    } catch (_: RuntimeException) {
        ExternalLaunchResult.Failed(
            "$failureMessage Android rejected the request.",
        )
    }
}
