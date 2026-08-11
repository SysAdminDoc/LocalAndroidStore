package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ArchiveRestoreStoreRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun pendingRestoreRoundTripsAndOnlyMatchingClearRemovesIt() {
        val store = ArchiveRestoreStore(context)
        store.clear()

        store.set("com.example.reader", 42)
        assertEquals(
            PendingArchiveRestore("com.example.reader", 42),
            store.pending(),
        )

        store.clearIf("com.example.other", 42)
        assertEquals(42, store.pending()?.unarchiveId)

        store.clearIf("com.example.reader", 42)
        assertNull(store.pending())
    }

    @Test
    fun invalidPackageNamesCannotBecomeRestoreRequests() {
        val store = ArchiveRestoreStore(context)

        assertThrows(IllegalArgumentException::class.java) {
            store.set("not a package", 42)
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.set("com.example.reader", -1)
        }
    }
}
