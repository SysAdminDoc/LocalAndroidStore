package com.sysadmin.lasstore.data

import com.sysadmin.lasstore.domain.DiscoveredApp
import com.sysadmin.lasstore.domain.Release
import com.sysadmin.lasstore.domain.ReleaseAsset
import com.sysadmin.lasstore.domain.SourcePlugin
import com.sysadmin.lasstore.domain.VerifyResult
import com.sysadmin.lasstore.domain.fdroidCategoryTag
import com.sysadmin.lasstore.domain.validateWhatsNew
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.jar.JarFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class GitLabProjectRecord(
    val id: String,
    val name: String,
    val description: String? = null,
    val webUrl: String,
    val archived: Boolean = false,
)

data class GitLabReleaseRecord(
    val id: String,
    val tagName: String,
    val versionName: String? = null,
    val publishedAt: String? = null,
    val prerelease: Boolean = false,
    val body: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)

interface GitLabGateway {
    suspend fun listProjects(user: String): List<GitLabProjectRecord>

    suspend fun listReleases(projectId: String, includePrereleases: Boolean): List<GitLabReleaseRecord>
}

class GitLabReleasesPlugin(
    private val gateway: GitLabGateway,
    private val user: String,
    private val includePrereleases: Boolean = false,
) : SourcePlugin {
    override val id: String = "gitlab:${user.trim().lowercase(Locale.US)}"
    override val displayName: String = user.trim()

    override suspend fun listApps(): List<DiscoveredApp> = gateway.listProjects(user.trim())
        .filterNot { it.archived }
        .map { project ->
            DiscoveredApp(
                applicationId = project.id,
                displayName = project.name,
                description = project.description,
                homepageUrl = project.webUrl,
            )
        }

    override suspend fun getReleases(applicationId: String): List<Release> =
        gateway.listReleases(applicationId, includePrereleases)
            .filter { includePrereleases || !it.prerelease }
            .map { release ->
                Release(
                    id = release.id,
                    applicationId = applicationId,
                    versionName = release.versionName
                        ?: release.tagName.removePrefix("v").removePrefix("V"),
                    publishedAt = release.publishedAt,
                    prerelease = release.prerelease,
                    body = release.body?.takeIf { it.isNotBlank() },
                    assets = release.assets,
                )
            }

    override suspend fun resolveDownloadUrl(release: Release): String =
        release.firstHttpsAssetUrl("GitLab release")

    override suspend fun verify(release: Release): VerifyResult =
        verifyReleaseAsset(release, "GitLab release")
}

data class FdroidRepositoryMetadata(
    val address: String,
    val version: Int?,
    val name: String?,
    val fingerprint: String?,
)

data class FdroidVersion(
    val versionCode: Long,
    val versionName: String?,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val minSdk: Int? = null,
    val maxSdk: Int? = null,
    /** ABIs this build ships native code for. Empty means an architecture-independent build. */
    val nativeCode: List<String> = emptyList(),
    val whatsNew: String? = null,
)

data class FdroidPackage(
    val packageName: String,
    val displayName: String,
    val description: String?,
    val antiFeatures: Set<String>,
    val categories: Set<String> = emptySet(),
    val versions: List<FdroidVersion>,
)

data class FdroidIndexV2(
    val repository: FdroidRepositoryMetadata,
    val packages: List<FdroidPackage>,
)

object FdroidIndexV2Parser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, baseUrl: String? = null): FdroidIndexV2 {
        val root = json.parseToJsonElement(raw).jsonObject
        val repo = root["repo"]?.jsonObject
            ?: throw IOException("F-Droid index is missing repo metadata")
        val address = repo.string("address")?.trimEnd('/')
            ?: baseUrl?.trimEnd('/')
            ?: throw IOException("F-Droid index is missing a repository address")
        requireHttps(address, "F-Droid repository address")

        val packages = root["packages"]?.jsonObject.orEmpty().map { (packageName, value) ->
            parsePackage(packageName, value.jsonObject, address)
        }
        return FdroidIndexV2(
            repository = FdroidRepositoryMetadata(
                address = address,
                version = repo.long("version")?.toInt(),
                name = repo.localized("name"),
                fingerprint = repo.string("fingerprint")?.let(::normalizeFingerprint),
            ),
            packages = packages,
        )
    }

    private fun parsePackage(
        packageName: String,
        value: JsonObject,
        repositoryAddress: String,
    ): FdroidPackage {
        require(packageName.isNotBlank()) { "F-Droid package id must not be blank" }
        val metadata = value["metadata"]?.jsonObject ?: value
        val versions = value["versions"]?.jsonObject.orEmpty()
            .mapNotNull { (versionKey, versionValue) ->
                parseVersion(packageName, versionKey, versionValue.jsonObject, repositoryAddress)
            }
            .sortedByDescending { it.versionCode }
        return FdroidPackage(
            packageName = packageName,
            displayName = metadata.localized("name") ?: packageName,
            description = metadata.localized("description")
                ?: metadata.localized("summary"),
            antiFeatures = metadata.antiFeatures(),
            categories = metadata.stringSet("categories"),
            versions = versions,
        )
    }

    private fun parseVersion(
        packageName: String,
        versionKey: String,
        value: JsonObject,
        repositoryAddress: String,
    ): FdroidVersion? {
        val manifest = value["manifest"]?.jsonObject ?: value
        val file = value["file"]?.jsonObject
            ?: value["apk"]?.jsonObject
            ?: return null
        val fileName = file.string("name")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = manifest.long("versionCode") ?: versionKey.toLongOrNull() ?: return null
        val versionName = manifest.string("versionName") ?: value.string("versionName")
        val minSdk = manifest["usesSdk"]?.jsonObject?.long("minSdkVersion")
            ?: manifest.long("minSdkVersion")
        val maxSdk = manifest["usesSdk"]?.jsonObject?.long("maxSdkVersion")
            ?: manifest.long("maxSdkVersion")
        val whatsNew = (value.localized("whatsNew") ?: manifest.localized("whatsNew"))
            ?.let(::validateWhatsNew)
        val downloadUrl = resolveAssetUrl(repositoryAddress, fileName)
        return FdroidVersion(
            versionCode = versionCode,
            versionName = versionName,
            downloadUrl = downloadUrl,
            fileName = fileName,
            sizeBytes = file.long("size") ?: 0L,
            sha256 = file.string("sha256"),
            minSdk = minSdk?.toInt(),
            maxSdk = maxSdk?.toInt(),
            nativeCode = manifest.stringSet("nativecode").toList(),
            whatsNew = whatsNew,
        )
    }

    private fun resolveAssetUrl(repositoryAddress: String, fileName: String): String {
        val absolute = fileName.toHttpUrlOrNull()
        if (absolute != null) {
            requireHttps(absolute, "F-Droid APK URL")
            return absolute.toString()
        }
        return requireNotNull(
            "$repositoryAddress/${fileName.trimStart('/')}".toHttpUrlOrNull(),
        ) { "F-Droid APK URL is malformed" }.toString()
    }
}

data class FdroidRepositoryEndpoint(
    val indexUrl: String,
    val expectedFingerprint: String,
)

object FdroidRepositoryTrust {
    fun parseEndpoint(rawUrl: String): FdroidRepositoryEndpoint {
        val parsed = rawUrl.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("F-Droid index URL is malformed")
        require(parsed.scheme.equals("https", ignoreCase = true)) {
            "F-Droid index URL must use HTTPS"
        }
        val fingerprint = normalizeFingerprint(parsed.queryParameter("fingerprint"))
            ?: throw IllegalArgumentException(
                "F-Droid index URL must include ?fingerprint=SHA256",
            )
        val indexUrl = parsed.newBuilder()
            .removeAllQueryParameters("fingerprint")
            .build()
            .toString()
        return FdroidRepositoryEndpoint(indexUrl, fingerprint)
    }

    fun matches(expectedFingerprint: String, observedFingerprint: String?): Boolean =
        normalizeFingerprint(expectedFingerprint) != null &&
            normalizeFingerprint(expectedFingerprint) == normalizeFingerprint(observedFingerprint)

    fun canonicalEndpoint(rawUrl: String): String {
        val endpoint = parseEndpoint(rawUrl)
        val index = requireNotNull(endpoint.indexUrl.toHttpUrlOrNull())
        return index.newBuilder()
            .addQueryParameter("fingerprint", endpoint.expectedFingerprint)
            .build()
            .toString()
    }

    fun sourceKey(rawUrl: String): String {
        val base = runCatching { parseEndpoint(rawUrl).indexUrl }
            .getOrElse { rawUrl.trim() }
        return "fdroid:${base.lowercase(Locale.US)}"
    }

    fun displayName(rawUrl: String): String = runCatching {
        requireNotNull(parseEndpoint(rawUrl).indexUrl.toHttpUrlOrNull()).host
    }.getOrElse { "F-Droid repository" }
}

class FDroidIndexV2Plugin(
    private val indexProvider: suspend () -> String,
    private val baseUrl: String,
    private val expectedFingerprint: String,
    override val id: String = "fdroid:${baseUrl.lowercase(Locale.US)}",
) : SourcePlugin {
    override val displayName: String = "F-Droid"

    override suspend fun listApps(): List<DiscoveredApp> = trustedIndex().packages.map { app ->
        DiscoveredApp(
            applicationId = app.packageName,
            displayName = app.displayName,
            description = app.description,
            homepageUrl = null,
            antiFeatures = app.antiFeatures,
            tags = app.categories.mapNotNull(::fdroidCategoryTag).toSet(),
        )
    }

    override suspend fun getReleases(applicationId: String): List<Release> =
        trustedIndex().packages
            .firstOrNull { it.packageName == applicationId }
            ?.versions
            .orEmpty()
            .map { version ->
                Release(
                    id = "$applicationId:${version.versionCode}",
                    applicationId = applicationId,
                    versionName = version.versionName,
                    versionCode = version.versionCode,
                    minSdk = version.minSdk,
                    maxSdk = version.maxSdk,
                    nativeCode = version.nativeCode,
                    body = version.whatsNew,
                    assets = listOf(
                        ReleaseAsset(
                            id = version.fileName,
                            name = version.fileName.substringAfterLast('/'),
                            downloadUrl = version.downloadUrl,
                            sizeBytes = version.sizeBytes,
                            sha256 = version.sha256,
                        ),
                    ),
                )
            }

    override suspend fun resolveDownloadUrl(release: Release): String =
        release.firstHttpsAssetUrl("F-Droid release")

    override suspend fun verify(release: Release): VerifyResult =
        verifyReleaseAsset(release, "F-Droid release")

    private suspend fun trustedIndex(): FdroidIndexV2 {
        val index = FdroidIndexV2Parser.parse(indexProvider(), baseUrl)
        if (!FdroidRepositoryTrust.matches(expectedFingerprint, index.repository.fingerprint)) {
            throw SecurityException("F-Droid repository fingerprint does not match the TOFU pin")
        }
        return index
    }
}

class IzzyOnDroidPlugin(
    indexProvider: suspend () -> String,
    baseUrl: String,
    expectedFingerprint: String,
) : SourcePlugin {
    private val delegate = FDroidIndexV2Plugin(
        indexProvider = indexProvider,
        baseUrl = baseUrl,
        expectedFingerprint = expectedFingerprint,
        id = "izzyondroid:${baseUrl.lowercase(Locale.US)}",
    )

    override val id: String get() = delegate.id
    override val displayName: String = "IzzyOnDroid"
    override suspend fun listApps(): List<DiscoveredApp> = delegate.listApps()
    override suspend fun getReleases(applicationId: String): List<Release> =
        delegate.getReleases(applicationId)
    override suspend fun resolveDownloadUrl(release: Release): String =
        delegate.resolveDownloadUrl(release)
    override suspend fun verify(release: Release): VerifyResult = delegate.verify(release)
}

/** Verifies signed entry.jar contents after all entries have been read by JarFile. */
object FdroidEntryJarVerifier {
    fun verify(file: File, expectedFingerprint: String): VerifyResult {
        if (!file.isFile) return VerifyResult.Rejected("F-Droid entry.jar is unavailable")
        return try {
            var certificateFingerprint: String? = null
            JarFile(file, true).use { jar ->
                jar.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .forEach { entry ->
                        jar.getInputStream(entry).use { input ->
                            while (input.read() != -1) {
                                // Drain the entry so JarFile verifies its signature.
                            }
                        }
                        val certificate = entry.certificates?.firstOrNull()
                        if (certificate != null) {
                            certificateFingerprint = certificateFingerprint
                                ?: certificate.encoded.sha256Hex()
                        }
                    }
            }
            if (FdroidRepositoryTrust.matches(expectedFingerprint, certificateFingerprint)) {
                VerifyResult.Verified(certificateFingerprint)
            } else {
                VerifyResult.Rejected("F-Droid entry.jar signer does not match the TOFU pin")
            }
        } catch (throwable: Throwable) {
            VerifyResult.Rejected("F-Droid entry.jar signature could not be verified")
        }
    }
}

private fun Release.firstHttpsAssetUrl(source: String): String {
    val asset = assets.firstOrNull()
        ?: throw IllegalArgumentException("$source has no downloadable asset")
    val url = asset.downloadUrl.toHttpUrlOrNull()
    require(url?.scheme.equals("https", ignoreCase = true)) {
        "$source download URL must use HTTPS"
    }
    return url.toString()
}

private fun verifyReleaseAsset(release: Release, source: String): VerifyResult {
    val asset = release.assets.firstOrNull()
        ?: return VerifyResult.Rejected("$source has no downloadable asset")
    val digest = normalizeSha256Digest(asset.sha256)
        ?: return VerifyResult.Unverified("$source did not publish a valid SHA-256 digest")
    return VerifyResult.Verified(digest)
}

private fun JsonObject.string(key: String): String? = this[key]
    ?.jsonPrimitive
    ?.contentOrNull
    ?.takeIf { it.isNotBlank() }

private fun JsonObject.long(key: String): Long? = this[key]?.let { value ->
    value.jsonPrimitive.contentOrNull?.toLongOrNull()
}

private fun JsonObject.localized(key: String): String? = this[key].localized()

private fun JsonElement?.localized(): String? = when (this) {
    is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
    is JsonObject -> sequenceOf("en-US", "en", "en_US")
        .mapNotNull { key -> this[key]?.localized() }
        .firstOrNull()
        ?: values.asSequence().mapNotNull { it.localized() }.firstOrNull()
    is JsonArray -> firstNotNullOfOrNull { it.localized() }
    else -> null
}

private fun JsonObject.antiFeatures(): Set<String> {
    val value = this["antiFeatures"] ?: this["anti-features"] ?: return emptySet()
    return when (value) {
        is JsonObject -> value.keys
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
        else -> emptySet()
    }
}

private fun JsonObject.stringSet(key: String): Set<String> {
    val value = this[key] ?: return emptySet()
    return when (value) {
        is JsonObject -> value.keys
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
        is JsonPrimitive -> value.contentOrNull?.let(::setOf).orEmpty()
    }
}

private fun requireHttps(raw: String, label: String): HttpUrl {
    val url = raw.toHttpUrlOrNull()
    require(url?.scheme.equals("https", ignoreCase = true)) { "$label must use HTTPS" }
    return requireNotNull(url)
}

private fun requireHttps(url: HttpUrl, label: String): HttpUrl {
    require(url.scheme.equals("https", ignoreCase = true)) { "$label must use HTTPS" }
    return url
}

private fun normalizeFingerprint(raw: String?): String? {
    val normalized = raw?.filterNot { it == ':' || it == '-' || it.isWhitespace() }
        ?.lowercase(Locale.US)
    return normalized?.takeIf {
        it.length == 64 && it.all { character -> character in "0123456789abcdef" }
    }
}

private fun ByteArray.sha256Hex(): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02x".format(Locale.US, byte) }
