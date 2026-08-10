package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmin.lasstore.domain.AppInfo
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstallAuditLogInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val auditFile by lazy { File(context.filesDir, "logs/install.log") }
    private val rotatedAuditFile by lazy { File(context.filesDir, "logs/install.log.1") }

    @Before
    @After
    fun cleanup() {
        auditFile.delete()
        rotatedAuditFile.delete()
    }

    @Test
    fun publisherPinRecoveryWritesAuthorizationAndReplacementEvidence() {
        val audit = InstallAuditLog(context)
        val info = appInfo()
        val metadata = metadata()

        assertTrue(
            audit.publisherPinRecoveryAuthorized(
                info = info,
                meta = metadata,
                previousPinSha256 = OLD_SIGNER,
                installedSignerSha256 = OLD_SIGNER,
            ),
        )
        assertTrue(
            audit.publisherPinReplacementPending(
                info = info,
                meta = metadata,
                previousPinSha256 = OLD_SIGNER,
                installedSignerSha256 = OLD_SIGNER,
            ),
        )
        assertTrue(
            audit.publisherPinReplaced(
                info = info,
                meta = metadata,
                previousPinSha256 = OLD_SIGNER,
                installedSignerSha256 = OLD_SIGNER,
            ),
        )

        val lines = auditFile.readLines()
        assertTrue(lines.size == 3)
        assertTrue(lines[0].contains("\"event\":\"publisher_pin_recovery_authorized\""))
        assertTrue(lines[0].contains("\"reason\":\"typed_package_plus_second_acknowledgement\""))
        assertTrue(lines[0].contains("\"previousCertSha256\":\"$OLD_SIGNER\""))
        assertTrue(lines[1].contains("\"event\":\"publisher_pin_replacement_pending\""))
        assertTrue(lines[2].contains("\"event\":\"publisher_pin_replaced\""))
        assertTrue(lines[2].contains("\"certSha256\":\"$NEW_SIGNER\""))
        assertTrue(lines[2].contains("\"verifiedSignatureSchemes\":[\"V3\"]"))
    }

    private fun appInfo() = AppInfo(
        owner = "owner",
        repo = "repo",
        sourceKey = "personal",
        sourceLabel = "Personal",
        displayName = "Example",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/repo",
        tagName = "v2",
        versionName = "2.0",
        versionCode = 2,
        applicationId = PACKAGE_NAME,
        asset = GhAsset(
            id = 2,
            name = "example.apk",
            browserDownloadUrl = "https://example.invalid/example.apk",
            size = 1,
        ),
        publishedAt = null,
        prerelease = false,
    )

    private fun metadata() = ApkMetadata(
        applicationId = PACKAGE_NAME,
        versionName = "2.0",
        versionCode = 2,
        label = "Example",
        signingSha256 = NEW_SIGNER,
        lineageSha256 = listOf(OLDER_SIGNER, NEW_SIGNER),
        verifiedSignatureSchemes = setOf(ApkSignatureScheme.V3),
    )

    private companion object {
        const val PACKAGE_NAME = "com.example.app"
        val OLDER_SIGNER = "01".repeat(32)
        val OLD_SIGNER = "12".repeat(32)
        val NEW_SIGNER = "34".repeat(32)
    }
}
