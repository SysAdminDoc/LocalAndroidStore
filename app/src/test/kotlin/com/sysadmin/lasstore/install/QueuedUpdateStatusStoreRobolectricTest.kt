package com.sysadmin.lasstore.install

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QueuedUpdateStatusStoreRobolectricTest {
    @Test
    fun tenThousandHistoricalStatusesArePrunedWithoutDroppingActiveWork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("queued_update_status", Context.MODE_PRIVATE)
        val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
        val now = System.currentTimeMillis()
        prefs.edit().clear().commit()

        val editor = prefs.edit()
        repeat(10_000) { index ->
            val status = status(
                workName = "historical-$index",
                phase = QueuedUpdatePhase.Failed,
                updatedAtEpochMillis = now - index,
            )
            editor.putString("status.${status.workName}", json.encodeToString(status))
        }
        val active = status(
            workName = "active",
            phase = QueuedUpdatePhase.Queued,
            updatedAtEpochMillis = now - QueuedUpdateStatusStore.TERMINAL_STATUS_RETENTION_MILLIS * 2,
        )
        editor.putString("status.${active.workName}", json.encodeToString(active))
        assertTrue(editor.commit())

        try {
            val store = QueuedUpdateStatusStore(context)
            store.awaitLoaded()

            val statuses = store.statuses.value
            assertTrue(statuses.any { it.workName == active.workName && it.isPending })
            assertTrue(
                statuses.size <= QueuedUpdateStatusStore.MAX_RETAINED_TERMINAL_STATUSES + 1,
            )
            assertFalse(statuses.any { it.workName == "historical-9999" })
            assertTrue(
                prefs.all.count { (key, _) -> key.startsWith("status.") } <=
                    QueuedUpdateStatusStore.MAX_RETAINED_TERMINAL_STATUSES + 1,
            )
        } finally {
            prefs.edit().clear().commit()
        }
    }

    private fun status(
        workName: String,
        phase: QueuedUpdatePhase,
        updatedAtEpochMillis: Long,
    ) = QueuedUpdateStatus(
        workName = workName,
        sourceKey = "github",
        owner = "owner",
        repo = workName,
        displayName = workName,
        phase = phase,
        attempt = 0,
        maxAttempts = QueuedUpdateStatusStore.MAX_ATTEMPTS,
        message = "test",
        updatedAtEpochMillis = updatedAtEpochMillis,
    )
}
