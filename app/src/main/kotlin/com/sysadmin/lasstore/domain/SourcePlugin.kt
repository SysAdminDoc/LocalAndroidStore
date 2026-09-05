package com.sysadmin.lasstore.domain

import java.util.Locale

/**
 * Stable source-local application identity. A plugin may use an Android package name when it is
 * known, or a source-native identifier such as `owner/repository` when discovery precedes APK
 * inspection.
 */
data class DiscoveredApp(
    val applicationId: String,
    val displayName: String,
    val description: String? = null,
    val homepageUrl: String? = null,
    val antiFeatures: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val repositoryArchived: Boolean = false,
    val repositoryLastActivityAt: String? = null,
)

data class ReleaseAsset(
    val id: String,
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

data class Release(
    val id: String,
    val applicationId: String,
    val versionName: String?,
    val versionCode: Long? = null,
    val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val body: String? = null,
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    /** ABIs this build ships native code for. Empty means an architecture-independent build. */
    val nativeCode: List<String> = emptyList(),
    val assets: List<ReleaseAsset> = emptyList(),
)

/** What this device can run, as the catalog needs to know it to pick a build. */
data class DeviceBuildSupport(
    /** `Build.VERSION.SDK_INT`, or null when the caller cannot supply it. */
    val sdkInt: Int? = null,
    /** `Build.SUPPORTED_ABIS`, most preferred first. Empty disables ABI filtering. */
    val supportedAbis: List<String> = emptyList(),
) {
    fun supports(release: Release): Boolean {
        if (sdkInt != null) {
            release.minSdk?.let { if (it > sdkInt) return false }
            release.maxSdk?.let { if (it < sdkInt) return false }
        }
        if (supportedAbis.isEmpty() || release.nativeCode.isEmpty()) return true
        return release.nativeCode.any { abi -> supportedAbis.any { it.equals(abi, true) } }
    }

    /** Lower is better. Architecture-independent builds sort after every native match. */
    private fun abiRank(release: Release): Int {
        if (release.nativeCode.isEmpty()) return supportedAbis.size
        return release.nativeCode
            .minOfOrNull { abi ->
                supportedAbis.indexOfFirst { it.equals(abi, true) }.takeIf { it >= 0 }
                    ?: supportedAbis.size
            }
            ?: supportedAbis.size
    }

    /**
     * The newest release this device can actually install. Highest version code wins, because
     * F-Droid gives each architecture its own code and the newest compatible build is what the
     * user wants; the device's ABI order only breaks ties between equal codes.
     */
    fun selectInstallable(releases: List<Release>): Release? = releases
        .filter(::supports)
        .minWithOrNull(
            compareByDescending<Release> { it.versionCode ?: Long.MIN_VALUE }
                .thenBy(::abiRank)
                .thenByDescending { it.versionName.orEmpty() },
        )
}

sealed interface VerifyResult {
    data class Verified(val sha256: String? = null) : VerifyResult
    data class Unverified(val reason: String) : VerifyResult
    data class Rejected(val reason: String) : VerifyResult
}

/** Four-callback boundary shared by catalog source implementations. */
interface SourcePlugin {
    val id: String
    val displayName: String

    suspend fun listApps(): List<DiscoveredApp>

    suspend fun getReleases(applicationId: String): List<Release>

    suspend fun resolveDownloadUrl(release: Release): String

    suspend fun verify(release: Release): VerifyResult
}

class SourcePluginRegistry(plugins: Iterable<SourcePlugin>) {
    private val pluginsById = plugins
        .onEach { require(it.id.isNotBlank()) { "Source plugin id must not be blank" } }
        .groupBy { it.id.trim().lowercase(Locale.US) }
        .also { grouped ->
            require(grouped.none { it.value.size > 1 }) {
                "Source plugin ids must be unique"
            }
        }
        .mapValues { it.value.single() }

    val plugins: List<SourcePlugin> = pluginsById.values.toList()

    fun find(id: String): SourcePlugin? = pluginsById[id.trim().lowercase(Locale.US)]
}
