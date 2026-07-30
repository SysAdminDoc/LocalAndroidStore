package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PackageVisibilityInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun knownPackageCanBeResolvedWithVersionAndCurrentSigner() {
        val installed = InstallStateRepo(context).info(context.packageName)

        assertNotNull(installed)
        assertEquals(context.packageName, installed?.applicationId)
        assertNotNull(installed?.currentSignerSha256)
        assertEquals(64, installed?.currentSignerSha256?.length)
    }

    @Test
    fun unknownPackageFailsClosedWithoutInventoryFallback() {
        assertNull(
            InstallStateRepo(context).info(
                "com.example.package.that.is.not.installed",
            ),
        )
    }
}
