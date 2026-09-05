package com.sysadmin.lasstore.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceBuildSupportTest {
    @Test
    fun prefersTheNewestCompatibleBuildOverAHigherIncompatibleVersionCode() {
        val selected = arm64Device.selectInstallable(
            listOf(
                release(versionCode = 4030, nativeCode = listOf("x86_64")),
                release(versionCode = 4020, nativeCode = listOf("arm64-v8a")),
                release(versionCode = 4010, nativeCode = listOf("armeabi-v7a")),
            ),
        )

        assertEquals(4020L, selected?.versionCode)
    }

    @Test
    fun neverSelectsABuildBelowTheDeviceSdkFloor() {
        val selected = arm64Device.selectInstallable(
            listOf(
                release(versionCode = 20, minSdk = 34),
                release(versionCode = 10, minSdk = 26),
            ),
        )

        assertEquals(10L, selected?.versionCode)
    }

    @Test
    fun neverSelectsABuildWhoseMaxSdkIsBelowTheDevice() {
        val selected = arm64Device.selectInstallable(
            listOf(
                release(versionCode = 20, maxSdk = 28),
                release(versionCode = 10),
            ),
        )

        assertEquals(10L, selected?.versionCode)
    }

    @Test
    fun reportsNoInstallableBuildWhenEveryVersionIsIncompatible() {
        val selected = arm64Device.selectInstallable(
            listOf(
                release(versionCode = 30, nativeCode = listOf("x86_64")),
                release(versionCode = 20, minSdk = 99),
                release(versionCode = 10, maxSdk = 24),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun acceptsArchitectureIndependentBuilds() {
        val universal = release(versionCode = 5)

        assertTrue(arm64Device.supports(universal))
        assertEquals(5L, arm64Device.selectInstallable(listOf(universal))?.versionCode)
    }

    @Test
    fun breaksVersionCodeTiesByTheDeviceAbiOrder() {
        val selected = arm64Device.selectInstallable(
            listOf(
                release(versionCode = 7, versionName = "universal"),
                release(versionCode = 7, versionName = "arm64", nativeCode = listOf("arm64-v8a")),
            ),
        )

        assertEquals("arm64", selected?.versionName)
    }

    @Test
    fun filtersNothingWhenTheDeviceCapabilitiesAreUnknown() {
        val unknownDevice = DeviceBuildSupport()
        val onlyBuild = release(versionCode = 3, minSdk = 99, nativeCode = listOf("mips"))

        assertTrue(unknownDevice.supports(onlyBuild))
    }

    @Test
    fun matchesAbiNamesCaseInsensitively() {
        assertTrue(arm64Device.supports(release(versionCode = 1, nativeCode = listOf("ARM64-V8A"))))
        assertFalse(arm64Device.supports(release(versionCode = 1, nativeCode = listOf("mips"))))
    }

    private fun release(
        versionCode: Long,
        versionName: String? = versionCode.toString(),
        minSdk: Int? = null,
        maxSdk: Int? = null,
        nativeCode: List<String> = emptyList(),
    ) = Release(
        id = "com.example.app:$versionCode:${versionName.orEmpty()}",
        applicationId = "com.example.app",
        versionName = versionName,
        versionCode = versionCode,
        minSdk = minSdk,
        maxSdk = maxSdk,
        nativeCode = nativeCode,
        assets = listOf(
            ReleaseAsset(
                id = "app-$versionCode.apk",
                name = "app-$versionCode.apk",
                downloadUrl = "https://example.org/app-$versionCode.apk",
            ),
        ),
    )

    private companion object {
        val arm64Device = DeviceBuildSupport(
            sdkInt = 33,
            supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        )
    }
}
