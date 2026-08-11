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
import java.security.MessageDigest
import java.util.UUID
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
    val operationId: String = "",
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
        operationId: String = newOperationId(),
    ): ForegroundInstallOperation {
        val operation = ForegroundInstallOperation(
            key = key(info),
            operationId = operationId,
            info = info,
            phase = ForegroundInstallPhase.Downloading,
            apkPath = apk.absolutePath,
            referrerUrl = referrerUrl,
        )
        synchronized(lock) {
            val previous = _operations.value.firstOrNull { it.key == operation.key }
            val next = _operations.value.filterNot { it.key == operation.key } + operation
            persist(next)
            _operations.value = next
            previous?.takeIf { it.apkPath != operation.apkPath }?.let(::deleteFiles)
        }
        return operation
    }

    fun isCurrent(key: String, operationId: String): Boolean =
        get(key)?.operationId == operationId

    /** Move durable ownership from a downloaded archive to its extracted APK-set directory. */
    fun setArtifactPath(
        key: String,
        operationId: String,
        artifact: File,
    ): ForegroundInstallOperation? = transform(key, operationId) {
        it.copy(apkPath = artifact.absolutePath)
    }

    fun markPreapproving(
        key: String,
        operationId: String,
        sessionId: Int,
    ): ForegroundInstallOperation? = transform(key, operationId) {
            it.copy(
                phase = ForegroundInstallPhase.Preapproving,
                preapprovalSessionId = sessionId,
            )
        }

    fun markPreapproving(key: String, sessionId: Int): ForegroundInstallOperation? =
        get(key)?.let { markPreapproving(key, it.operationId, sessionId) }

    fun markDownloading(
        key: String,
        operationId: String,
        preapprovalSessionId: Int?,
    ): ForegroundInstallOperation? = transform(key, operationId) {
            it.copy(
                phase = ForegroundInstallPhase.Downloading,
                preapprovalSessionId = preapprovalSessionId,
            )
        }

    fun markDownloading(key: String, preapprovalSessionId: Int?): ForegroundInstallOperation? =
        get(key)?.let { markDownloading(key, it.operationId, preapprovalSessionId) }

    fun markPermissionReview(
        key: String,
        operationId: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        preapprovalSessionId: Int?,
        permissions: List<String>,
    ): ForegroundInstallOperation? = transform(key, operationId) {
        it.copy(
            phase = ForegroundInstallPhase.PermissionReview,
            metadata = metadata,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedAlready = installedAlready,
            preapprovalSessionId = preapprovalSessionId,
            newDangerousPermissions = permissions,
        )
    }

    fun markPermissionReview(
        key: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        preapprovalSessionId: Int?,
        permissions: List<String>,
    ): ForegroundInstallOperation? = get(key)?.let {
        markPermissionReview(
            key = key,
            operationId = it.operationId,
            metadata = metadata,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedAlready = installedAlready,
            preapprovalSessionId = preapprovalSessionId,
            permissions = permissions,
        )
    }

    fun markCommitting(
        key: String,
        operationId: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        installerSessionId: Int,
    ): ForegroundInstallOperation? = transform(key, operationId) {
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

    fun markCommitting(
        key: String,
        metadata: ApkMetadata,
        pinnedSignerSha256: String?,
        installedAlready: Boolean,
        installerSessionId: Int,
    ): ForegroundInstallOperation? = get(key)?.let {
        markCommitting(
            key = key,
            operationId = it.operationId,
            metadata = metadata,
            pinnedSignerSha256 = pinnedSignerSha256,
            installedAlready = installedAlready,
            installerSessionId = installerSessionId,
        )
    }

    fun remove(key: String, deleteApk: Boolean = true): ForegroundInstallOperation? =
        removeInternal(key, expectedOperationId = null, deleteApk = deleteApk)

    fun removeIfCurrent(
        key: String,
        operationId: String,
        deleteApk: Boolean = true,
    ): ForegroundInstallOperation? =
        removeInternal(key, expectedOperationId = operationId, deleteApk = deleteApk)

    private fun removeInternal(
        key: String,
        expectedOperationId: String?,
        deleteApk: Boolean,
    ): ForegroundInstallOperation? =
        synchronized(lock) {
            val removed = _operations.value.firstOrNull { it.key == key } ?: return@synchronized null
            if (expectedOperationId != null && removed.operationId != expectedOperationId) {
                return@synchronized null
            }
            val next = _operations.value.filterNot { it.key == key }
            persist(next)
            _operations.value = next
            if (deleteApk) deleteFiles(removed)
            removed
        }

    fun apkFile(operation: ForegroundInstallOperation): File? {
        val cacheRoot = File(context.cacheDir, APK_CACHE_DIR)
        val file = File(operation.apkPath)
        val rootPath = runCatching { cacheRoot.canonicalFile.toPath() }.getOrNull() ?: return null
        val filePath = runCatching { file.canonicalFile.toPath() }.getOrNull() ?: return null
        return file.takeIf { filePath.startsWith(rootPath) }
    }

    /**
     * Stable partial-download location for the currently published asset.
     *
     * The foreground operation itself deliberately keeps a random final APK path so a new
     * install action cannot attach to an old completed artifact. The partial path is keyed by
     * the source, release asset URL, and published digest so an interrupted transfer can safely
     * be resumed after process death without crossing release or source boundaries.
     */
    fun partialDownloadFile(info: AppInfo): File {
        val directory = File(context.cacheDir, "$APK_CACHE_DIR/$PARTIAL_DOWNLOAD_DIR")
            .apply { mkdirs() }
        val identity = listOf(
            key(info),
            info.tagName,
            info.asset.id.toString(),
            info.asset.name,
            info.asset.browserDownloadUrl,
            info.asset.size.toString(),
            info.asset.digest.orEmpty(),
        ).joinToString("\n")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return File(directory, "$digest.part")
    }

    fun partialDownloadSize(info: AppInfo): Long = partialDownloadFile(info)
        .takeIf { it.isFile && it.length() > 0L }
        ?.length()
        ?: 0L

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
                if (isActive) {
                    false
                } else if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
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
        operationId: String,
        block: (ForegroundInstallOperation) -> ForegroundInstallOperation,
    ): ForegroundInstallOperation? {
        val current = get(key) ?: return null
        if (current.operationId != operationId) return null
        return update(block(current))
    }

    private fun deleteFiles(operation: ForegroundInstallOperation) {
        apkFile(operation)?.let { file ->
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        partialFile(operation)?.delete()
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

        fun newOperationId(): String = UUID.randomUUID().toString()

        private const val PREFS_NAME = "foreground_install_state"
        private const val KEY_OPERATIONS = "operations"
        private const val KEY_PENDING_MEDIA = "pending_media_store_uris"
        private const val APK_CACHE_DIR = "apks"
        private const val PARTIAL_DOWNLOAD_DIR = ".partial"
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
            ?.takeIf {
                it.phase == ForegroundInstallPhase.Committing &&
                    (registration.operationId == null || it.operationId == registration.operationId)
            }
            ?: return false
        val metadata = operation.metadata ?: run {
            sl.foregroundInstalls.removeIfCurrent(operation.key, operation.operationId)
            logger.warn("Installer", "Foreground install result lacked persisted APK metadata")
            return true
        }
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
        if (status == PackageInstaller.STATUS_SUCCESS) {
            if (!InstallTrustStateFinalizer.finalizeSuccessfulInstall(
                    info = operation.info,
                    metadata = metadata,
                    previousPinnedSignerSha256 = operation.pinnedSignerSha256,
                    logger = logger,
                )
            ) return false
            logger.info(
                "Install",
                "Installed ${metadata.applicationId} ${metadata.versionName.orEmpty()}",
            )
        } else {
            val decoded = decodeFailure(context, status, systemMessage)
            sl.audit.installFailed(operation.info, metadata, decoded)
            logger.warn("Install", "Install failed for ${metadata.applicationId}: $decoded")
        }
        sl.foregroundInstalls.removeIfCurrent(operation.key, operation.operationId)
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
        val finalized = handleTerminal(
            context = ServiceLocator.appContext,
            intent = synthetic,
            registration = InstallResultRegistration(
                capability = "reconciled",
                sessionId = operation.installerSessionId ?: return false,
                applicationId = metadata.applicationId,
                route = InstallResultRoute.Foreground,
                operationId = operation.operationId,
            ),
            logger = logger,
        )
        return finalized || ServiceLocator.foregroundInstalls.get(operation.key) != null
    }
}
