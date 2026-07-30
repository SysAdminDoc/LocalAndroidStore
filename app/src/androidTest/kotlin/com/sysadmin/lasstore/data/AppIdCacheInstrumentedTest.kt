package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmin.lasstore.domain.AppInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppIdCacheInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearCache() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun sameRepositoryCanStoreIndependentSourceIdentities() {
        val cache = AppIdCache(context)
        cache.recordInstalled(
            app(sourceKey = "source-a", assetId = 101),
            metadata(applicationId = "com.example.a", versionCode = 10, signer = "AA"),
        )
        cache.recordInstalled(
            app(sourceKey = "source-b", assetId = 202),
            metadata(applicationId = "com.example.b", versionCode = 20, signer = "BB"),
        )

        val sourceA = cache.get("source-a", OWNER, REPO)
        val sourceB = cache.get("source-b", OWNER, REPO)
        assertEquals("com.example.a", sourceA?.applicationId)
        assertEquals(10L, sourceA?.installedVersionCode)
        assertEquals("AA", sourceA?.installedSignerSha256)
        assertEquals(101L, sourceA?.installedAsset?.assetId)
        assertEquals("com.example.b", sourceB?.applicationId)
        assertEquals(20L, sourceB?.installedVersionCode)
        assertEquals("BB", sourceB?.installedSignerSha256)
        assertEquals(202L, sourceB?.installedAsset?.assetId)
    }

    @Test
    fun ambiguousLegacyIdentityCanOnlyBeClaimedByOneSource() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("appid:$OWNER/$REPO", "com.example.legacy")
            .putString("tag:$OWNER/$REPO", "v1")
            .commit()
        val cache = AppIdCache(context)

        assertEquals(
            "com.example.legacy",
            cache.get("source-a", OWNER, REPO)?.applicationId,
        )
        assertNull(cache.get("source-b", OWNER, REPO))
    }

    @Test
    fun externalVersionChangeInvalidatesRecordedAssetAssociation() {
        val cache = AppIdCache(context)
        val entry = cache.recordInstalled(
            app(sourceKey = "source-a", assetId = 101),
            metadata(applicationId = "com.example.a", versionCode = 10, signer = "AA"),
        )

        val reconciled = cache.reconcileInstalled(
            entry = entry,
            installed = InstalledInfo(
                applicationId = "com.example.a",
                versionName = "11",
                versionCode = 11,
            ),
            pinnedSignerSha256 = "AA",
        )

        assertEquals(11L, reconciled.installedVersionCode)
        assertNull(reconciled.installedAsset)
    }

    private fun app(sourceKey: String, assetId: Long) = AppInfo(
        owner = OWNER,
        repo = REPO,
        sourceKey = sourceKey,
        sourceLabel = sourceKey,
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/$OWNER/$REPO",
        tagName = "v$assetId",
        versionName = null,
        versionCode = null,
        applicationId = null,
        asset = GhAsset(
            id = assetId,
            name = "example.apk",
            browserDownloadUrl = "https://example.invalid/$sourceKey.apk",
            size = assetId,
        ),
        publishedAt = null,
        prerelease = false,
    )

    private fun metadata(
        applicationId: String,
        versionCode: Long,
        signer: String,
    ) = ApkMetadata(
        applicationId = applicationId,
        versionName = versionCode.toString(),
        versionCode = versionCode,
        label = "Example",
        signingSha256 = signer,
    )

    private companion object {
        const val PREFS_NAME = "las_appid_cache"
        const val OWNER = "Owner"
        const val REPO = "Repo"
    }
}
