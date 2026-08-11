package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

@Serializable
data class ApkLockfileEntry(
    val applicationId: String,
    val versionCode: Long,
    val apkSha256: String,
    val certSha256: String,
    val sourceUrl: String,
    val manifestSha256: String,
)

@Serializable
data class ApkLockfileDocument(
    val formatVersion: Int = 1,
    val generatedAtEpochMillis: Long = 0L,
    val entries: List<ApkLockfileEntry> = emptyList(),
)

/** Atomic, app-private lockfile for APKs successfully installed through LocalAndroidStore. */
class ApkLockfileStore(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, "las.lock")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    @Synchronized
    fun recordInstalled(info: AppInfo, metadata: ApkMetadata): ApkLockfileEntry {
        val sourcePath = contextPackagePath(info, metadata.applicationId)
        val digests = digestApkFile(sourcePath)
        val entry = ApkLockfileEntry(
            applicationId = metadata.applicationId,
            versionCode = metadata.versionCode,
            apkSha256 = digests.apkSha256,
            certSha256 = metadata.signingSha256,
            sourceUrl = info.asset.browserDownloadUrl,
            manifestSha256 = digests.manifestSha256.orEmpty(),
        )
        val previous = read()
        val document = previous.copy(
            generatedAtEpochMillis = System.currentTimeMillis(),
            entries = (previous.entries
                .filterNot { it.applicationId == entry.applicationId } + entry)
                .sortedBy { it.applicationId },
        )
        write(document)
        return entry
    }

    @Synchronized
    fun read(): ApkLockfileDocument = file.takeIf { it.isFile }
        ?.let { candidate ->
            runCatching { json.decodeFromString<ApkLockfileDocument>(candidate.readText()) }
                .getOrNull()
        }
        ?: ApkLockfileDocument()

    fun file(): File = file

    private fun contextPackagePath(info: AppInfo, applicationId: String): File {
        require(applicationId == info.applicationId || info.applicationId == null) {
            "Installed package does not match the catalog package"
        }
        val packageInfo = appContext.packageManager.getApplicationInfo(applicationId, 0)
        return File(packageInfo.sourceDir).also { source ->
            require(source.isFile && source.canRead()) {
                "Installed APK path is unavailable for $applicationId"
            }
        }
    }

    private fun write(document: ApkLockfileDocument) {
        file.parentFile?.mkdirs()
        val temporary = File(requireNotNull(file.parentFile), "${file.name}.tmp")
        temporary.writeText(json.encodeToString(document))
        runCatching {
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }.recoverCatching {
            Files.move(temporary.toPath(), file.toPath(), REPLACE_EXISTING)
        }.getOrElse { failure ->
            temporary.delete()
            throw IllegalStateException("Could not atomically replace ${file.name}", failure)
        }
    }
}
