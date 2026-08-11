package com.sysadmin.lasstore.install

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.sysadmin.lasstore.data.ApkInspectionResult
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.InstallArtifactKind
import com.sysadmin.lasstore.data.installArtifactKind
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** One verified APK inside a split/archive release. */
internal data class SplitArtifactEntry(
    val id: String,
    val displayName: String,
    val splitName: String?,
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    val size: Long,
    val selectedByDefault: Boolean,
    val file: File,
)

/** Files and base metadata ready for one PackageInstaller session. */
internal data class PreparedInstallArtifact(
    val installRoot: File,
    val files: List<File>,
    val baseFile: File,
    val metadata: ApkMetadata,
    val entries: List<SplitArtifactEntry>,
) {
    val isSplitInstall: Boolean get() = files.size > 1

    fun retain(selectedIds: Set<String>): PreparedInstallArtifact {
        val selected = entries.filter { it.id in selectedIds }
        require(selected.any { it.file == baseFile }) { "A split install must include its base APK." }
        require(selected.isNotEmpty()) { "At least one APK must be selected." }
        entries.filterNot { it.id in selectedIds }.forEach { entry ->
            if (entry.file.exists() && !entry.file.delete()) {
                throw IOException("Could not remove unselected split ${entry.displayName}")
            }
        }
        return copy(
            files = selected.map { it.file },
            entries = selected,
        )
    }

    fun cleanup() {
        if (installRoot.isDirectory) installRoot.deleteRecursively()
    }
}

internal class InstallArtifactException(message: String) : IOException(message)

/**
 * Verifies a downloaded APK-set archive before it reaches PackageInstaller.
 *
 * ZIP extraction is deliberately bounded and path-safe. Every extracted APK is checked with the
 * same apksig + PackageManager policy used for a standalone artifact; package, version, and
 * current signer must agree across the complete selected set.
 */
internal class BundleArtifactPreparer(
    private val context: Context,
    private val inspector: com.sysadmin.lasstore.data.ApkInspector,
) {
    fun prepare(
        source: File,
        stagingRoot: File,
        expectedApplicationId: String? = null,
    ): PreparedInstallArtifact {
        return when (installArtifactKind(source.name)) {
            InstallArtifactKind.APK -> prepareStandalone(source, expectedApplicationId)
            InstallArtifactKind.ZIP_APK_SET -> prepareZipSet(source, stagingRoot, expectedApplicationId)
            InstallArtifactKind.AAB -> throw InstallArtifactException(
                "Android App Bundles need bundletool extraction before they can be installed. " +
                    "Publish an .apks, .xapk, or .apkm set for on-device installation.",
            )
            InstallArtifactKind.UNSUPPORTED -> throw InstallArtifactException(
                "This release asset is not a supported Android install artifact.",
            )
        }
    }

    private fun prepareStandalone(
        source: File,
        expectedApplicationId: String?,
    ): PreparedInstallArtifact {
        val metadata = inspect(source)
        checkExpectedApplication(expectedApplicationId, metadata.applicationId)
        val entry = SplitArtifactEntry(
            id = source.name,
            displayName = source.name,
            splitName = null,
            packageName = metadata.applicationId,
            versionCode = metadata.versionCode,
            signerSha256 = metadata.signingSha256,
            size = source.length(),
            selectedByDefault = true,
            file = source,
        )
        return PreparedInstallArtifact(
            installRoot = source,
            files = listOf(source),
            baseFile = source,
            metadata = metadata,
            entries = listOf(entry),
        )
    }

    private fun prepareZipSet(
        source: File,
        stagingRoot: File,
        expectedApplicationId: String?,
    ): PreparedInstallArtifact {
        if (!source.isFile || source.length() <= 0L) {
            throw InstallArtifactException("The APK set archive is empty or unreadable.")
        }
        if (stagingRoot.exists() && !stagingRoot.deleteRecursively()) {
            throw InstallArtifactException("Could not replace the previous extracted APK set.")
        }
        if (!stagingRoot.mkdirs() && !stagingRoot.isDirectory) {
            throw InstallArtifactException("Could not create private APK-set staging storage.")
        }

        try {
            val extracted = ZipFile(source).use { zip ->
                val apkEntries = zip.entries().asSequence()
                    .filter { entry ->
                        !entry.isDirectory &&
                            entry.name.lowercase(Locale.US).endsWith(".apk") &&
                            !entry.name.lowercase(Locale.US).endsWith(".apk.idsig")
                    }
                    .toList()
                if (apkEntries.isEmpty()) {
                    throw InstallArtifactException("The APK set contains no APK entries.")
                }
                if (apkEntries.size > MAX_SPLIT_COUNT) {
                    throw InstallArtifactException("The APK set contains too many split APKs.")
                }
                var totalBytes = 0L
                apkEntries.mapIndexed { index, entry ->
                    val declaredSize = entry.size
                    if (declaredSize > MAX_SPLIT_BYTES) {
                        throw InstallArtifactException("Split ${entry.name} exceeds the per-file size limit.")
                    }
                    val outputName = "${index.toString().padStart(3, '0')}_" +
                        safeEntryName(entry.name.substringAfterLast('/'))
                    val output = File(stagingRoot, outputName)
                    zip.getInputStream(entry).use { input ->
                        output.outputStream().use { out ->
                            val copied = copyBounded(input, out, MAX_SPLIT_BYTES) { count ->
                                totalBytes += count
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    throw InstallArtifactException("The APK set exceeds the total size limit.")
                                }
                            }
                            if (copied <= 0L) {
                                throw InstallArtifactException("Split ${entry.name} is empty.")
                            }
                        }
                    }
                    output
                }
            }
            val descriptors = extracted.map { file ->
                val metadata = inspect(file)
                val splitName = archiveSplitName(file)
                SplitDescriptor(
                    originalName = file.name.substringAfter('_').ifBlank { file.name },
                    file = file,
                    metadata = metadata,
                    splitName = splitName,
                )
            }
            val packageName = descriptors.first().metadata.applicationId
            checkExpectedApplication(expectedApplicationId, packageName)
            descriptors.forEach { descriptor ->
                if (descriptor.metadata.applicationId != packageName) {
                    throw InstallArtifactException("Every split must belong to the same package.")
                }
                if (descriptor.metadata.versionCode != descriptors.first().metadata.versionCode) {
                    throw InstallArtifactException("Every split must use the same version code.")
                }
                if (descriptor.metadata.signingSha256 != descriptors.first().metadata.signingSha256) {
                    throw InstallArtifactException("Every split must use the same publisher certificate.")
                }
            }

            val base = findBase(descriptors)
            val normalizedDescriptors = descriptors.map { descriptor ->
                if (descriptor == base && descriptor.file.name != "base.apk") {
                    val renamed = File(stagingRoot, "base.apk")
                    if (!descriptor.file.renameTo(renamed)) {
                        throw InstallArtifactException("Could not normalize the base APK name.")
                    }
                    descriptor.copy(file = renamed)
                } else {
                    descriptor
                }
            }
            val baseDescriptor = normalizedDescriptors.first { it.originalName == base.originalName }
            val selectedDefaults = normalizedDescriptors.map { descriptor ->
                SplitArtifactEntry(
                    id = descriptor.file.name,
                    displayName = descriptor.originalName,
                    splitName = descriptor.splitName,
                    packageName = descriptor.metadata.applicationId,
                    versionCode = descriptor.metadata.versionCode,
                    signerSha256 = descriptor.metadata.signingSha256,
                    size = descriptor.file.length(),
                    selectedByDefault = descriptor == base || isCompatibleWithDevice(descriptor),
                    file = descriptor.file,
                )
            }
            val defaultIds = ensureOnePerConfiguration(selectedDefaults)
            val entries = selectedDefaults.map { entry ->
                entry.copy(selectedByDefault = entry.id in defaultIds)
            }
            val baseEntry = entries.first { it.file == baseDescriptor.file }
            return PreparedInstallArtifact(
                installRoot = stagingRoot,
                files = entries.map { it.file },
                baseFile = baseEntry.file,
                metadata = baseDescriptor.metadata,
                entries = entries,
            )
        } catch (throwable: Throwable) {
            stagingRoot.deleteRecursively()
            if (throwable is InstallArtifactException) throw throwable
            throw InstallArtifactException(
                throwable.message ?: "Could not prepare the APK set.",
            )
        }
    }

    private fun inspect(file: File): ApkMetadata = when (val result = inspector.inspectResult(file)) {
        is ApkInspectionResult.Verified -> result.metadata
        is ApkInspectionResult.Rejected -> throw InstallArtifactException(
            "${file.name}: ${result.reason.userMessage}",
        )
    }

    private fun archiveSplitName(file: File): String? {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(0L),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
        }.getOrNull()
        return info?.splitNames?.singleOrNull()
    }

    private fun findBase(descriptors: List<SplitDescriptor>): SplitDescriptor {
        val namedBases = descriptors.filter { isBaseName(it.originalName) }
        if (namedBases.size == 1) return namedBases.single()
        val manifestBases = descriptors.filter { it.splitName == null }
        if (manifestBases.size == 1) return manifestBases.single()
        if (descriptors.size == 1) return descriptors.single()
        throw InstallArtifactException("The APK set must contain exactly one base APK.")
    }

    private fun isCompatibleWithDevice(descriptor: SplitDescriptor): Boolean {
        val token = listOfNotNull(descriptor.splitName, descriptor.originalName)
            .joinToString(" ")
            .lowercase(Locale.US)
        val abi = when {
            token.contains("arm64") || token.contains("aarch64") -> "arm64-v8a"
            token.contains("armeabi") || token.contains("armv7") -> "armeabi-v7a"
            token.contains("x86_64") || token.contains("x86-64") || token.contains("amd64") -> "x86_64"
            token.contains("x86") || token.contains("i686") -> "x86"
            else -> null
        }
        if (abi != null && abi != normalizeAbi(Build.SUPPORTED_ABIS.firstOrNull())) return false
        val density = DENSITIES.firstOrNull { value ->
            Regex("(^|[^a-z])$value([^a-z]|$)").containsMatchIn(token)
        }
        if (density != null && density != densityBucket(context.resources.displayMetrics.densityDpi)) {
            return false
        }
        val locale = localeToken(token)
        val locales = context.resources.configuration.locales
        if (locale != null && (0 until locales.size()).none { index ->
                val current = locales[index]
                current.language.equals(locale.substringBefore('-'), ignoreCase = true) ||
                    current.toLanguageTag().equals(locale, ignoreCase = true)
            }
        ) {
            return false
        }
        return true
    }

    private fun ensureOnePerConfiguration(entries: List<SplitArtifactEntry>): Set<String> {
        val selected = entries.filter { it.selectedByDefault }.mapTo(mutableSetOf()) { it.id }
        if (selected.isEmpty()) selected += entries.first { it.file.name == "base.apk" }.id
        return selected
    }

    private fun checkExpectedApplication(expected: String?, actual: String) {
        if (expected != null && !expected.equals(actual, ignoreCase = true)) {
            throw InstallArtifactException(
                "Downloaded artifact package changed from $expected to $actual.",
            )
        }
    }

    private data class SplitDescriptor(
        val originalName: String,
        val file: File,
        val metadata: ApkMetadata,
        val splitName: String?,
    )

    private companion object {
        const val MAX_SPLIT_COUNT = 64
        const val MAX_SPLIT_BYTES = 512L * 1024L * 1024L
        const val MAX_TOTAL_BYTES = 1_024L * 1024L * 1024L
        val DENSITIES = listOf("ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val LOCALE_PATTERN = Regex("(?:^|[._-])config[._-]([a-z]{2,3}(?:[-_][a-z]{2,4})?)(?:[._-]|$)")

        fun safeEntryName(name: String): String {
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return safe.takeLast(120).ifBlank { "split.apk" }
        }

        fun isBaseName(name: String): Boolean =
            name.substringAfterLast('/').lowercase(Locale.US).let { it == "base.apk" || it.startsWith("base-") && it.endsWith(".apk") }

        fun normalizeAbi(abi: String?): String? = when (abi?.lowercase(Locale.US)) {
            "arm64-v8a", "arm64_v8a", "aarch64" -> "arm64-v8a"
            "armeabi-v7a", "armeabi_v7a", "armv7", "armv7a" -> "armeabi-v7a"
            "x86_64", "x86-64", "amd64" -> "x86_64"
            "x86", "i686" -> "x86"
            else -> null
        }

        fun densityBucket(densityDpi: Int): String = when {
            densityDpi <= 120 -> "ldpi"
            densityDpi <= 160 -> "mdpi"
            densityDpi <= 240 -> "hdpi"
            densityDpi <= 320 -> "xhdpi"
            densityDpi <= 480 -> "xxhdpi"
            else -> "xxxhdpi"
        }

        fun localeToken(token: String): String? =
            LOCALE_PATTERN.find(token)?.groupValues?.getOrNull(1)?.replace('_', '-')

        fun copyBounded(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            limit: Long,
            onBytes: (Long) -> Unit,
        ): Long {
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count == -1) return total
                total += count
                if (total > limit) throw InstallArtifactException("APK set entry exceeds the size limit.")
                output.write(buffer, 0, count)
                onBytes(count.toLong())
            }
        }
    }
}
