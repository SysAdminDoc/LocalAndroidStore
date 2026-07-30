package com.sysadmin.lasstore.install

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.data.InstallStateRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlatformContractRobolectricTest {
    @Test
    @Config(sdk = [32])
    fun api32UsesWorkManagerAndKnownPackageQueries() {
        assertLegacyPlatformContract(expectedApi = 32)
    }

    @Test
    @Config(sdk = [33])
    fun api33UsesWorkManagerAndKnownPackageQueries() {
        assertLegacyPlatformContract(expectedApi = 33)
    }

    private fun assertLegacyPlatformContract(expectedApi: Int) {
        val context: Context = ApplicationProvider.getApplicationContext()

        assertEquals(expectedApi, Build.VERSION.SDK_INT)
        assertEquals(
            BackgroundUpdateTransport.WorkManager,
            backgroundUpdateTransportForApi(),
        )
        assertNotNull(InstallStateRepo(context).info(context.packageName))
        assertNull(
            InstallStateRepo(context).info(
                "com.example.package.that.is.not.installed",
            ),
        )
    }
}
