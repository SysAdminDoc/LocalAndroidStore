package com.sysadmin.lasstore.install

import android.content.Context
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.ApkSignatureScheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class QueuedUpdatePhase {
    Queued,
    Running,
    Retrying,
    AuditPending,
    AwaitingUserAction,
    Installed,
    Failed,
    Cancelled,
}

@Serializable
enum class QueuedUpdateFailureKind {
    Network,
    Timeout,
    RateLimited,
    Server,
    Authentication,
    Authorization,
    Signature,
    PackageIdentity,
    PermissionReview,
    Incompatible,
    UserCancelled,
    Policy,
    Storage,
    InvalidArtifact,
    AuditPending,
    Unknown,
}

@Serializable
data class QueuedUpdateStatus(
    val workName: String,
    val sourceKey: String,
    val owner: String,
    val repo: String,
    val displayName: String,
    val phase: QueuedUpdatePhase,
    val attempt: Int,
    val maxAttempts: Int,
    val message: String,
    val updatedAtEpochMillis: Long,
    val retryAtEpochMillis: Long? = null,
    val failureKind: QueuedUpdateFailureKind? = null,
    val packageInstallerSessionId: Int? = null,
    val generationId: String = "",
    val targetApplicationId: String? = null,
    val targetVersionCode: Long? = null,
    val targetVersionName: String? = null,
    val targetSignerSha256: String? = null,
    val targetLineageSha256: List<String> = emptyList(),
    val targetVerifiedSignatureSchemes: Set<ApkSignatureScheme> = emptySet(),
    val queuedPayload: QueuedUpdatePayload? = null,
) {
    val isPending: Boolean
        get() = phase == QueuedUpdatePhase.Queued ||
            phase == QueuedUpdatePhase.Running ||
            phase == QueuedUpdatePhase.Retrying ||
            phase == QueuedUpdatePhase.AuditPending ||
            phase == QueuedUpdatePhase.AwaitingUserAction
}

class QueuedUpdateStatusStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _statuses = MutableStateFlow(load())
    val statuses: StateFlow<List<QueuedUpdateStatus>> = _statuses.asStateFlow()

    fun get(payload: QueuedUpdatePayload): QueuedUpdateStatus? =
        _statuses.value.firstOrNull { it.workName == payload.workName }

    fun get(sourceKey: String, owner: String, repo: String): QueuedUpdateStatus? =
        _statuses.value.firstOrNull {
            it.sourceKey == sourceKey &&
                it.owner.equals(owner, ignoreCase = true) &&
                it.repo.equals(repo, ignoreCase = true)
        }

    fun isCurrent(payload: QueuedUpdatePayload): Boolean = synchronized(LOCK) {
        val status = currentStatusLocked(payload) ?: return@synchronized false
        status.generationId.isBlank() || status.generationId == payload.generationId
    }

    fun isFinalized(payload: QueuedUpdatePayload): Boolean = synchronized(LOCK) {
        currentStatusLocked(payload)?.phase?.isTerminal == true
    }

    /**
     * Run a terminal state transition while holding the generation lease.
     *
     * A replacement can arrive between two independent [isCurrent] checks. Keeping the
     * audit/pin/cache finalization inside this lock makes the check and the mutation one
     * operation: a late callback either completes before replacement, or is rejected in full.
     */
    fun <T> ifCurrent(payload: QueuedUpdatePayload, block: () -> T): T? = synchronized(LOCK) {
        val status = currentStatusLocked(payload) ?: return@synchronized null
        if (!isCurrentLocked(payload) || status.phase.isTerminal) return@synchronized null
        block()
    }

    fun markQueued(payload: QueuedUpdatePayload) {
        save(
            payload,
            phase = QueuedUpdatePhase.Queued,
            attempt = 0,
            message = "Queued for a gentle background update.",
            allowGenerationReplacement = true,
        )
    }

    fun beginAttempt(payload: QueuedUpdatePayload): Int {
        val attempt = ((get(payload)?.attempt ?: 0) + 1).coerceAtMost(MAX_ATTEMPTS + 1)
        val saved = save(
            payload,
            phase = QueuedUpdatePhase.Running,
            attempt = attempt,
            message = "Background update attempt $attempt of $MAX_ATTEMPTS.",
        )
        return if (saved) attempt else STALE_ATTEMPT
    }

    fun markRetrying(
        payload: QueuedUpdatePayload,
        attempt: Int,
        failure: QueuedUpdateResult.Failed,
    ) {
        save(
            payload,
            phase = QueuedUpdatePhase.Retrying,
            attempt = attempt,
            message = "${failure.message} Retrying ($attempt of $MAX_ATTEMPTS).",
            retryAtEpochMillis = failure.retryAtEpochMillis,
            failureKind = failure.kind,
        )
    }

    fun markFailed(
        payload: QueuedUpdatePayload,
        attempt: Int,
        failure: QueuedUpdateResult.Failed,
    ): Boolean = save(
            payload,
            phase = if (failure.kind == QueuedUpdateFailureKind.UserCancelled) {
                QueuedUpdatePhase.Cancelled
            } else {
                QueuedUpdatePhase.Failed
            },
            attempt = attempt,
            message = failure.message,
            failureKind = failure.kind,
        )

    fun markInstalled(payload: QueuedUpdatePayload, message: String = "Background update installed.") {
        synchronized(LOCK) {
            val current = currentStatusLocked(payload) ?: return@synchronized
            if (!isCurrentLocked(payload) || current.phase.isTerminal) return@synchronized
            save(
                payload,
                phase = QueuedUpdatePhase.Installed,
                attempt = current.attempt,
                message = message,
            )
        }
    }

    fun markAuditPending(
        payload: QueuedUpdatePayload,
        attempt: Int,
        message: String,
    ) {
        save(
            payload,
            phase = QueuedUpdatePhase.AuditPending,
            attempt = attempt,
            message = message,
            failureKind = QueuedUpdateFailureKind.AuditPending,
        )
    }

    fun markInstallerSession(
        payload: QueuedUpdatePayload,
        attempt: Int,
        packageInstallerSessionId: Int,
        metadata: ApkMetadata? = null,
    ): Boolean {
        val current = get(payload) ?: return false
        return save(
            payload = payload,
            phase = current.phase,
            attempt = attempt,
            message = current.message,
            retryAtEpochMillis = current.retryAtEpochMillis,
            failureKind = current.failureKind,
            packageInstallerSessionId = packageInstallerSessionId,
            targetApplicationId = metadata?.applicationId,
            targetVersionCode = metadata?.versionCode,
            targetVersionName = metadata?.versionName,
            targetSignerSha256 = metadata?.signingSha256,
            targetLineageSha256 = metadata?.lineageSha256,
            targetVerifiedSignatureSchemes = metadata?.verifiedSignatureSchemes,
        )
    }

    fun markAwaitingInstall(
        payload: QueuedUpdatePayload,
        attempt: Int,
        packageInstallerSessionId: Int,
    ): Boolean = synchronized(LOCK) {
        val current = currentStatusLocked(payload) ?: return@synchronized false
        if (!isCurrentLocked(payload) || current.phase.isTerminal) return@synchronized false
        save(
            payload,
            phase = QueuedUpdatePhase.Queued,
            attempt = attempt,
            message = "Download verified; waiting for Android's gentle install constraints.",
            packageInstallerSessionId = packageInstallerSessionId,
        )
    }

    fun markAwaitingUserAction(
        payload: QueuedUpdatePayload,
        attempt: Int,
        packageInstallerSessionId: Int,
        message: String = "Android needs your confirmation to finish this background update.",
    ): Boolean = synchronized(LOCK) {
        val current = currentStatusLocked(payload) ?: return@synchronized false
        if (!isCurrentLocked(payload) || current.phase.isTerminal) return@synchronized false
        save(
            payload,
            phase = QueuedUpdatePhase.AwaitingUserAction,
            attempt = attempt,
            message = message,
            packageInstallerSessionId = packageInstallerSessionId,
        )
    }

    fun markNeedsReschedule(payload: QueuedUpdatePayload, message: String): Boolean = synchronized(LOCK) {
        val current = currentStatusLocked(payload) ?: return@synchronized false
        if (!isCurrentLocked(payload) || current.phase.isTerminal) return@synchronized false
        save(
            payload,
            phase = QueuedUpdatePhase.Queued,
            attempt = current.attempt,
            message = message,
            packageInstallerSessionId = current.packageInstallerSessionId,
        )
    }

    fun shouldDeferForRateLimit(payload: QueuedUpdatePayload): Boolean =
        get(payload)?.retryAtEpochMillis?.let { it > System.currentTimeMillis() } == true

    fun markCancelled(payload: QueuedUpdatePayload) {
        save(
            payload,
            phase = QueuedUpdatePhase.Cancelled,
            attempt = get(payload)?.attempt ?: 0,
            message = "Background update cancelled.",
            failureKind = QueuedUpdateFailureKind.UserCancelled,
        )
    }

    private fun save(
        payload: QueuedUpdatePayload,
        phase: QueuedUpdatePhase,
        attempt: Int,
        message: String,
        retryAtEpochMillis: Long? = null,
        failureKind: QueuedUpdateFailureKind? = null,
        packageInstallerSessionId: Int? = null,
        targetApplicationId: String? = null,
        targetVersionCode: Long? = null,
        targetVersionName: String? = null,
        targetSignerSha256: String? = null,
        targetLineageSha256: List<String>? = null,
        targetVerifiedSignatureSchemes: Set<ApkSignatureScheme>? = null,
        allowGenerationReplacement: Boolean = false,
    ): Boolean = synchronized(LOCK) {
        if (!allowGenerationReplacement && !isCurrentLocked(payload)) return@synchronized false
        val previous = currentStatusLocked(payload).takeUnless { allowGenerationReplacement }
        val status = QueuedUpdateStatus(
            workName = payload.workName,
            sourceKey = payload.sourceKey,
            owner = payload.owner,
            repo = payload.repo,
            displayName = payload.displayName,
            phase = phase,
            attempt = attempt,
            maxAttempts = MAX_ATTEMPTS,
            message = message,
            updatedAtEpochMillis = System.currentTimeMillis(),
            retryAtEpochMillis = retryAtEpochMillis,
            failureKind = failureKind,
            packageInstallerSessionId = packageInstallerSessionId,
            generationId = payload.generationId,
            targetApplicationId = targetApplicationId ?: previous?.targetApplicationId,
            targetVersionCode = targetVersionCode ?: previous?.targetVersionCode,
            targetVersionName = targetVersionName ?: previous?.targetVersionName,
            targetSignerSha256 = targetSignerSha256 ?: previous?.targetSignerSha256,
            targetLineageSha256 = targetLineageSha256 ?: previous?.targetLineageSha256.orEmpty(),
            targetVerifiedSignatureSchemes = targetVerifiedSignatureSchemes
                ?: previous?.targetVerifiedSignatureSchemes.orEmpty(),
            queuedPayload = if (allowGenerationReplacement) {
                payload
            } else {
                previous?.queuedPayload ?: payload
            },
        )
        check(prefs.edit().putString(key(payload.workName), json.encodeToString(status)).commit()) {
            "Could not persist queued update status"
        }
        _statuses.value = load()
        true
    }

    private fun isCurrentLocked(payload: QueuedUpdatePayload): Boolean {
        val status = currentStatusLocked(payload) ?: return false
        return status.generationId.isBlank() || status.generationId == payload.generationId
    }

    private fun currentStatusLocked(payload: QueuedUpdatePayload): QueuedUpdateStatus? =
        _statuses.value.firstOrNull { it.workName == payload.workName }

    private fun load(): List<QueuedUpdateStatus> = prefs.all
        .filterKeys { it.startsWith(KEY_PREFIX) }
        .values
        .mapNotNull { encoded ->
            (encoded as? String)?.let {
                runCatching { json.decodeFromString<QueuedUpdateStatus>(it) }.getOrNull()
            }
        }
        .sortedByDescending { it.updatedAtEpochMillis }

    private fun key(workName: String): String = "$KEY_PREFIX$workName"

    companion object {
        const val MAX_ATTEMPTS = 3
        const val STALE_ATTEMPT = -1
        private const val PREFS_NAME = "queued_update_status"
        private const val KEY_PREFIX = "status."
        private val LOCK = Any()
    }
}

private val QueuedUpdatePhase.isTerminal: Boolean
    get() = this == QueuedUpdatePhase.Installed ||
        this == QueuedUpdatePhase.Failed ||
        this == QueuedUpdatePhase.Cancelled
