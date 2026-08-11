package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import java.time.LocalDate
import java.util.Locale

enum class UpdateCadenceMode {
    Auto,
    Notify,
    Pinned,
}

data class UpdateCadence(
    val mode: UpdateCadenceMode = UpdateCadenceMode.Auto,
    val heldUntilEpochMillis: Long? = null,
) {
    fun isHeld(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        heldUntilEpochMillis?.let { it > nowEpochMillis } == true
}

/** Persists per-app background update policy and an atomic local-day auto-queue budget. */
class UpdateCadenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun get(info: AppInfo): UpdateCadence {
        val rawMode = preferences.getString(key(info), null)
            ?: return UpdateCadence()
        val mode = runCatching { UpdateCadenceMode.valueOf(rawMode) }
            .getOrDefault(UpdateCadenceMode.Auto)
        val heldUntil = preferences.getLong(holdKey(info), 0L).takeIf { it > 0L }
        return UpdateCadence(mode = mode, heldUntilEpochMillis = heldUntil)
    }

    @Synchronized
    fun set(info: AppInfo, cadence: UpdateCadence) {
        preferences.edit()
            .putString(key(info), cadence.mode.name)
            .apply {
                if (cadence.heldUntilEpochMillis != null) {
                    putLong(holdKey(info), cadence.heldUntilEpochMillis)
                } else {
                    remove(holdKey(info))
                }
            }
            .apply()
    }

    /** Reserve one auto-policy slot for the current device-local day. */
    @Synchronized
    fun tryReserveDailySlot(
        dailyCap: Int,
        now: LocalDate = LocalDate.now(),
    ): Boolean {
        if (dailyCap <= 0) return false
        val date = now.toString()
        val storedDate = preferences.getString(DAILY_DATE_KEY, null)
        val storedCount = if (storedDate == date) {
            preferences.getInt(DAILY_COUNT_KEY, 0)
        } else {
            0
        }
        if (storedCount >= dailyCap) return false
        return preferences.edit()
            .putString(DAILY_DATE_KEY, date)
            .putInt(DAILY_COUNT_KEY, storedCount + 1)
            .commit()
    }

    @Synchronized
    fun releaseDailySlot(now: LocalDate = LocalDate.now()) {
        val date = now.toString()
        if (preferences.getString(DAILY_DATE_KEY, null) != date) return
        val count = preferences.getInt(DAILY_COUNT_KEY, 0)
        preferences.edit().putInt(DAILY_COUNT_KEY, (count - 1).coerceAtLeast(0)).commit()
    }

    private fun key(info: AppInfo): String = "cadence:${identity(info)}"

    private fun holdKey(info: AppInfo): String = "hold:${identity(info)}"

    private fun identity(info: AppInfo): String =
        info.applicationId?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotBlank() }
            ?: "${info.sourceKey}/${info.owner}/${info.repo}"
                .trim()
                .lowercase(Locale.US)

    private companion object {
        const val PREFERENCES_NAME = "las_update_cadence_v1"
        const val DAILY_DATE_KEY = "daily_auto_date"
        const val DAILY_COUNT_KEY = "daily_auto_count"
    }
}
