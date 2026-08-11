package com.sysadmin.lasstore.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sysadmin.lasstore.domain.AppInfo
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UpdateCadenceStoreRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun perAppPolicyAndDatedHoldSurviveAStoreReload() {
        clearPreferences()
        val info = appInfo()
        val holdUntil = 1_800_000_000_000L
        UpdateCadenceStore(context).set(
            info,
            UpdateCadence(UpdateCadenceMode.Auto, heldUntilEpochMillis = holdUntil),
        )

        val saved = UpdateCadenceStore(context).get(info)
        assertEquals(UpdateCadenceMode.Auto, saved.mode)
        assertEquals(holdUntil, saved.heldUntilEpochMillis)
        assertTrue(saved.isHeld(holdUntil - 1L))
        assertFalse(saved.isHeld(holdUntil))
    }

    @Test
    fun dailyAutoReservationsRespectTheCapAndReleaseOnQueueFailure() {
        clearPreferences()
        val store = UpdateCadenceStore(context)
        val day = LocalDate.of(2026, 8, 11)

        assertTrue(store.tryReserveDailySlot(2, day))
        assertTrue(store.tryReserveDailySlot(2, day))
        assertFalse(store.tryReserveDailySlot(2, day))
        store.releaseDailySlot(day)
        assertTrue(store.tryReserveDailySlot(2, day))
        assertFalse(store.tryReserveDailySlot(2, day))
    }

    private fun clearPreferences() {
        context.getSharedPreferences("las_update_cadence_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun appInfo() = AppInfo(
        owner = "owner",
        repo = "app",
        sourceKey = "github",
        sourceLabel = "GitHub",
        displayName = "App",
        description = null,
        stars = 0,
        htmlUrl = "https://github.com/owner/app",
        tagName = "v1",
        versionName = "1",
        versionCode = null,
        applicationId = "com.example.app",
        asset = GhAsset(
            name = "app.apk",
            browserDownloadUrl = "https://example.invalid/app.apk",
        ),
        publishedAt = null,
        prerelease = false,
    )
}
