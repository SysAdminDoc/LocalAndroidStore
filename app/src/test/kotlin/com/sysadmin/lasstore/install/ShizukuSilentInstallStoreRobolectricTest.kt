package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShizukuSilentInstallStoreRobolectricTest {
    @Test
    fun preferenceDefaultsOffAndSurvivesASecondStoreInstance() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("las_shizuku_install_v1", Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .commit()

        val first = ShizukuSilentInstallStore(context)
        assertFalse(first.isEnabled())
        assertTrue(first.setEnabled(true))
        assertTrue(ShizukuSilentInstallStore(context).isEnabled())

        preferences.edit().clear().commit()
    }
}
