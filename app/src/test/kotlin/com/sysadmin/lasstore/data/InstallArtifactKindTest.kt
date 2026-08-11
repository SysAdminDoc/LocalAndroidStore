package com.sysadmin.lasstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallArtifactKindTest {
    @Test
    fun recognizesSupportedArchiveExtensionsWithoutAcceptingSourceZips() {
        assertEquals(InstallArtifactKind.APK, installArtifactKind("release.APK"))
        assertEquals(InstallArtifactKind.ZIP_APK_SET, installArtifactKind("release.apks"))
        assertEquals(InstallArtifactKind.ZIP_APK_SET, installArtifactKind("release.xapk"))
        assertEquals(InstallArtifactKind.ZIP_APK_SET, installArtifactKind("release.apkm"))
        assertEquals(InstallArtifactKind.AAB, installArtifactKind("release.aab"))
        assertFalse(isInstallableArtifactName("source.zip"))
        assertTrue(isInstallableArtifactName("release.apks"))
    }
}
