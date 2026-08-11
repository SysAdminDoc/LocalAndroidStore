package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BatchUninstallStoreRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun persistsOneAtATimeConfirmationQueue() {
        val preferences = context.getSharedPreferences("las_batch_uninstall_v1", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val first = BatchUninstallEntry("com.example.one", "One", "owner/one")
            val second = BatchUninstallEntry("com.example.two", "Two", "owner/two")
            val store = BatchUninstallStore(context)

            store.begin(listOf(first, second))
            assertEquals(first, store.peek())
            assertFalse(store.isAwaitingConfirmation())

            store.markAwaitingConfirmation(true)
            assertTrue(BatchUninstallStore(context).isAwaitingConfirmation())
            assertTrue(store.remove(first.applicationId))
            assertEquals(second, BatchUninstallStore(context).peek())

            store.clear()
            assertEquals(emptyList<BatchUninstallEntry>(), store.entries())
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
