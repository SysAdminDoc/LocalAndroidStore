package com.sysadmin.lasstore.install

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.Logger
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.signerMatchesVerifiedArtifact
import com.sysadmin.lasstore.domain.AppInfo
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ForegroundInstallPhase {
    Preapproving,
    Downloading,
    PermissionReview,
    Committing,
}

@Serializable
data class ForegroundInstallOperation(
    val key: String,
    val info: AppInfo,
    val phase: ForegroundInstallPhase,
    val apkPath: String,
    val referrerUrl: String,
    val metadata: ApkMetadata? = null,
    val pinnedSignerSha256: String? = null,
    val installedAlready: Boolean = false,
    val preapprovalSessionId: Int? = null,
    val installerSessionId: Int? = null,
    val newDangerousPermissions: List<String> = emptyList(),
)

/**
 * Durable ownership record for foreground install work.
 *
 * Only files inside this app's APK cache directory are accepted during recovery or cleanup.
 */
class ForegroundInstallStore(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()
    private val _operations = MutableStateFlow(load())
    val operations: StateFlow<List<ForegroundInstallOperation>> = _operations.asStateFlow()

    fun get(key: String): ForegroundInstallOperation? =
        _operations.value.firstOrNull { it.key == key }

    fun findBySession(sessionId: Int): ForegroundInstallOperation? =
        _operations.value.firstOrNull {
            it.installerSessionId == sessionId || it.preapprovalSessionId == sessionId
        }

    fun start(
        info: AppInfo,
        apk: File,
        referrerUrl: String,
    ): ForegroundInstallOperation = update(
        ForegroundInstallOperation(
            key = key(info),
            info = info,
            phase = ForegroundInstallPhase.Downloading,
            apkPath = apk.absolutePath,
            referrerUrl = referrerUrl,
        ),
    )

    fun markPreapproving(key: String, sessionId: Int): ForegroundInstallOperation? =
        transform(key) {
            it.copy(
                phase = ForegroundInstallPhase.Preapproving,
                preapprovalSessionId = sessionId,
            )
        }

    fun markDownloading(key: String, preapprovalSessionId: Int?): ForegroundInstallOperation? =
        transform(key) {
            it.copy(
                phase = ForegroundInstallPhase.Downloading,
                preapprovalSessionId = preapprovalSessionId,
            )
        }

    fun markPermissionReview(
        key: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        preapprovalSessionId: Int?,
        permissions: List<String>,
    ): ForegroundInstallOperation? = transform(key) {
        it.copy(
            phase = ForegroundInstallPhase.PermissionReview,
            metadata = metadata,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedAlready = installedAlready,
            preapprovalSessionId = preapprovalSessionId,
            newDangerousPermissions = permissions,
        )
    }

    fun markCommitting(
        key: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        installerSessionId: Int,
    ): ForegroundInstallOperation? = transform(key) {
        it.copy(
            phase = ForegroundInstallPhase.Committing,
            metadata = metadata,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedAlready = installedAlready,
            installerSessionId = installerSessionId,
            preapprovalSessionId = null,
            newDangerousPermissions = emptyList(),
        )
    }

    fun remove(key: String, deleteApk: Boolean = true): ForegroundInstallOperation? =
        synchronized(lock) {
            val removed = _operations.value.firstOrNull { it.key == key } ?: return@synchronized null
            val next = _operations.value.filterNot { it.key == key }
            persist(next)
            _operations.value = next
            if (deleteApk) apkFile(removed)?.delete()
            partialFile(removed)?.delete()
            removed
        }

    fun apkFile(operation: ForegroundInstallOperation): File? {
        val cacheRoot = File(context.cacheDir, APK_CACHE_DIR)
        val file = File(operation.apkPath)
        val rootPath = runCatching { cacheRoot.canonicalFile.toPath() }.getOrNull() ?: return null
        val filePath = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return null
        return file.takeIf { filePath.startsWith(rootPath) }
    }

    fun addPendingMediaStoreUri(uri: Uri) {
        synchronized(lock) {
            val values = prefs.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toMutableSet()
            values += uri.toString()
            prefs.edit().putStringSet(KEY_PENDING_MEDIA, values).commit()
        }
    }

    fun completePendingMediaStoreUri(uri: Uri) {
        synchronized(lock) {
            val values = prefs.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toMutableSet()
            values -= uri.toString()
            prefs.edit().putStringSet(KEY_PENDING_MEDIA, values).commit()
        }
    }

    fun cleanupPendingMediaStoreRows(): Int {
        val values = synchronized(lock) {
            prefs.getStringSet(KEY_PENDING_MEDIA, emptySet()).orEmpty().toSet()
        }
        values.forEach { encoded ->
            runCatching { context.contentResolver.delete(Uri.parse(encoded), null, null) }
        }
        synchronized(lock) {
            prefs.edit().remove(KEY_PENDING_MEDIA).commit()
        }
        return values.size
    }

    fun cleanupOrphanedApkFiles(): Int {
        val activePaths = _operations.value.mapNotNull(::apkFile)
            .mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            .toSet()
        val directory = File(context.cacheDir, APK_CACHE_DIR)
        return directory.listFiles()
            .orEmpty()
            .count { file ->
                val finalPath = if (file.name.endsWith(".part")) {
                    file.absolutePath.removeSuffix(".part")
                } else {
                    file.absolutePath
                }
                val isActive = runCatching { File(finalPath).canonicalPath in activePaths }
                    .getOrDefault(false)
                !isActive && file.delete()
            }
    }

    private fun update(operation: ForegroundInstallOperation): ForegroundInstallOperation =
        synchronized(lock) {
            val next = _operations.value.filterNot { it.key == operation.key } + operation
            persist(next)
            _operations.value = next
            operation
        }

    private fun transform(
        key: String,
        block: (ForegroundInstallOperation) -> ForegroundInstallOperation,
    ): ForegroundInstallOperation? {
        val current = get(key) ?: return null
        return update(block(current))
    }

    private fun load(): List<ForegroundInstallOperation> =
        prefs.getString(KEY_OPERATIONS, null)
            ?.let { encoded ->
                runCatching {
                    json.decodeFromString<List<ForegroundInstallOperation>>(encoded)
                }.getOrNull()
            }
            .orEmpty()

    private fun persist(operations: List<ForegroundInstallOperation>) {
        check(
            prefs.edit()
                .putString(KEY_OPERATIONS, json.encodeToString(operations))
                .commit()
        ) { "Could not persist foreground install state" }
    }

    private fun partialFile(operation: ForegroundInstallOperation): File? =
        apkFile(operation)?.let { File("${it.absolutePath}.part") }

    companion object {
        fun key(info: AppInfo): String = "${info.sourceKey}/${info.owner}/${info.repo}"

        private const val PREFS_NAME = "foreground_install_state"
        private const val KEY_OPERATIONS = "operations"
        private const val KEY_PENDING_MEDIA = "pending_media_store_uris"
        private const val APK_CACHE_DIR = "apks"
    }
}

internal object ForegroundInstallFinalizer {
    fun handleTerminal(
        context: Context,
        intent: Intent,
        registration: InstallResultRegistration,
        logger: Logger,
    ): Boolean {
        ServiceLocator.init(context.applicationContext)
        val sl = ServiceLocator
        val operation = sl.foregroundInstalls.findBySession(registration.sessionId)
            ?.takeIf { it.phase == ForegroundInstallPhase.Committing }
            ?: return false
        val metadata = operation.metadata ?: run {
            sl.foregroundInstalls.remove(operation.key)
            logger.warn("Installer", "Foreground install result lacked persisted APK metadata")
            return true
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_SUCCESS) {
            val previousPin = operation.pinnedSignerSha256
            if (!metadata.isEligibleForPinEnrollment) {
                logger.error(
                    "Install",
                    "Installed ${metadata.applicationId}, but refused unverified signer-pin enrollment",
                )
            } else if (previousPin.isNullOrBlank()) {
                sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
            } else if (
                previousPin != metadata.signingSha256 &&
                previousPin in metadata.lineageSha256
            ) {
                sl.secrets.setPin(metadata.applicationId, metadata.signingSha256)
                logger.info(
                    "Install",
                    "Rolled pin forward for ${metadata.applicationId}: " +
                        "$previousPin -> ${metadata.signingSha256}",
                )
            }
            sl.appIdCache.recordInstalled(operation.info, metadata)
            sl.audit.installSucceeded(operation.info, metadata)
            logger.info(
                "Install",
                "Installed ${metadata.applicationId} ${metadata.versionName.orEmpty()}",
            )
        } else {
            val decoded = decodeFailure(context, status, systemMessage)
            sl.audit.installFailed(operation.info, metadata, decoded)
            logger.warn("Install", "Install failed for ${metadata.applicationId}: $decoded")
        }
        sl.foregroundInstalls.remove(operation.key)
        return true
    }

    fun reconcileCompletedOperation(
        operation: ForegroundInstallOperation,
        logger: Logger,
    ): Boolean {
        val metadata = operation.metadata ?: return false
        val installed = ServiceLocator.installState.info(metadata.applicationId) ?: return false
        if (installed.versionCode != metadata.versionCode) return false
        if (!signerMatchesVerifiedArtifact(installed.currentSignerSha256, metadata.signingSha256)) {
            logger.warn(
                "Installer",
                "Refusing to reconcile ${metadata.applicationId}: installed signer does not " +
                    "match the verified APK metadata",
            )
            return false
        }
        val synthetic = Intent()
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS)
        return handleTerminal(
            context = ServiceLocator.appContext,
            intent = synthetic,
            registration = InstallResultRegistration(
                capability = "reconciled",
                sessionId = operation.installerSessionId ?: return false,
                applicationId = metadata.applicationId,
                route = InstallResultRoute.Foreground,
            ),
            logger = logger,
        )
    }
}
