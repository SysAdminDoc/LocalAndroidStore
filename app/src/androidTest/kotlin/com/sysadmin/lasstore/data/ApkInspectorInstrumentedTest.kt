package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ApkInspectorInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val inspector = ApkInspector(context)
    private val fixtureDirectory by lazy { File(context.cacheDir, "apk-verification-fixtures") }
    private val pinPackage = "com.example.lasstore.verification.fixture"

    @After
    fun cleanup() {
        fixtureDirectory.listFiles().orEmpty().forEach(File::delete)
        fixtureDirectory.delete()
        SecretStore(context).clearPin(pinPackage)
    }

    @Test
    fun installedDebugApkPassesRealApksigAndPackageManagerCrossCheck() {
        val result = inspector.inspectResult(File(context.applicationInfo.sourceDir))

        assertTrue(result is ApkInspectionResult.Verified)
        val metadata = (result as ApkInspectionResult.Verified).metadata
        assertEquals(context.packageName, metadata.applicationId)
        assertEquals(64, metadata.signingSha256.length)
        assertTrue(metadata.verifiedSignatureSchemes.isNotEmpty())
        assertTrue(metadata.isEligibleForPinEnrollment)
        assertEquals(
            metadata.signingSha256,
            InstallStateRepo(context).info(context.packageName)?.currentSignerSha256,
        )
    }

    @Test
    fun oneByteTamperIsRejectedByRealApksig() {
        fixtureDirectory.mkdirs()
        val tampered = File(fixtureDirectory, "tampered.apk")
        File(context.applicationInfo.sourceDir).copyTo(tampered)
        RandomAccessFile(tampered, "rw").use { file ->
            val offset = file.length() / 2
            file.seek(offset)
            val original = file.read()
            file.seek(offset)
            file.write(original xor 0xff)
        }

        val result = inspector.inspectResult(tampered)

        assertTrue(result is ApkInspectionResult.Rejected)
        val rejection = result as ApkInspectionResult.Rejected
        assertEquals(ApkRejectionReason.SIGNATURE_NOT_VERIFIED, rejection.reason)
        assertTrue(rejection.reason.isSignatureFailure)
    }

    @Test
    fun malformedArtifactCannotBecomeAStoredPin() {
        fixtureDirectory.mkdirs()
        val malformed = File(fixtureDirectory, "malformed.apk").apply {
            writeText("not an apk")
        }
        val result = inspector.inspectResult(malformed)
        val secrets = SecretStore(context)

        assertTrue(result is ApkInspectionResult.Rejected)
        assertFalse(secrets.getPin(pinPackage).orEmpty().isNotEmpty())
        val invalidPin = runCatching { secrets.setPin(pinPackage, "AA") }
        assertTrue(invalidPin.isFailure)
        assertFalse(secrets.getPin(pinPackage).orEmpty().isNotEmpty())
    }
}
