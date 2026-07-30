package com.sysadmin.lasstore.install

import android.content.Context
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmin.lasstore.data.ApkMetadata
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.domain.AppInfo
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundInstallStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val cacheDirectory by lazy { File(context.cacheDir, "apks") }

    @Before
    @After
    fun cleanup() {
        context.getSharedPreferences("foreground_install_state", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        cacheDirectory.listFiles().orEmpty().forEach(File::delete)
    }

    @Test
    fun permissionReviewRoundTripsAcrossStoreRecreation() {
        val apk = File(cacheDirectory.apply(File::mkdirs), "review.apk").apply {
            writeText("fixture")
        }
        val original = ForegroundInstallStore(context)
        val operation = original.start(appInfo(), apk, appInfo().asset.browserDownloadUrl)
        original.markPermissionReview(
            key = operation.key,
            metadata = metadata(),
            pinnedSignerSha256 = "AA",
            installedAlready = true,
            preapprovalSessionId = 42,
            permissions = listOf("android.permission.CAMERA"),
        )

        val restored = ForegroundInstallStore(context).get(operation.key)

        assertNotNull(restored)
        assertEquals(ForegroundInstallPhase.PermissionReview, restored?.phase)
        assertEquals(42, restored?.preapprovalSessionId)
        assertEquals(listOf("android.permission.CAMERA"), restored?.newDangerousPermissions)
        assertEquals(apk.canonicalPath, ForegroundInstallStore(context).apkFile(restored!!)?.canonicalPath)
    }

    @Test
    fun removalDeletesFinalAndPartialCacheFiles() {
        val apk = File(cacheDirectory.apply(File::mkdirs), "cancel.apk").apply {
            writeText("fixture")
        }
        val partial = File("${apk.absolutePath}.part").apply { writeText("partial") }
        val store = ForegroundInstallStore(context)
        val operation = store.start(appInfo(), apk, appInfo().asset.browserDownloadUrl)

        store.remove(operation.key)

        assertFalse(apk.exists())
        assertFalse(partial.exists())
        assertNull(store.get(operation.key))
    }

    @Test
    fun manifestReceiverFinalizesPersistedForegroundFailureWithoutLiveCallback() {
        ServiceLocator.init(context)
        val apk = File(cacheDirectory.apply(File::mkdirs), "commit.apk").apply {
            writeText("fixture")
        }
        val store = ServiceLocator.foregroundInstalls
        val operation = store.start(appInfo(), apk, appInfo().asset.browserDownloadUrl)
        store.markCommitting(
            key = operation.key,
            metadata = metadata(),
            pinnedSignerSha256 = "AA",
            installedAlready = true,
            installerSessionId = SESSION_ID,
        )
        val registration = InstallResultRegistry(context).register(
            sessionId = SESSION_ID,
            applicationId = APPLICATION_ID,
            route = InstallResultRoute.Foreground,
        )
        val intent = installResultIntent(context, registration)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, SESSION_ID)
            .putExtra(PackageInstaller.EXTRA_PACKAGE_NAME, APPLICATION_ID)
            .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED)

        InstallResultReceiver().onReceive(context, intent)

        assertNull(store.get(operation.key))
        assertFalse(apk.exists())
    }

    private fun appInfo() = AppInfo(
        owner = "owner",
        repo = "repo",
        sourceKey = "source",
        sourceLabel = "Source",
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/repo",
        tagName = "v2",
        versionName = "2",
        versionCode = 2,
        applicationId = APPLICATION_ID,
        asset = GhAsset(
            id = 22,
            name = "example.apk",
            browserDownloadUrl = "https://example.invalid/example.apk",
            size = 7,
        ),
        publishedAt = null,
        prerelease = false,
    )

    private fun metadata() = ApkMetadata(
        applicationId = APPLICATION_ID,
        versionName = "2",
        versionCode = 2,
        label = "Example",
        signingSha256 = "AA",
    )

    private companion object {
        const val APPLICATION_ID = "com.example.foreground"
        const val SESSION_ID = 9_812
    }
}
