package com.sysadmin.lasstore.data

import android.content.Context
import com.sysadmin.lasstore.domain.AppInfo
import com.sysadmin.lasstore.domain.ReleaseChannel
import java.util.Locale

/** Persists the preferred release channel for each source-local repository stream. */
class ChannelPreferenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "las_release_channels_v1",
        Context.MODE_PRIVATE,
    )

    fun get(sourceKey: String, owner: String, repo: String): ReleaseChannel? =
        ReleaseChannel.fromKey(preferences.getString(key(sourceKey, owner, repo), null))

    fun get(info: AppInfo): ReleaseChannel? = get(info.sourceKey, info.owner, info.repo)

    fun set(sourceKey: String, owner: String, repo: String, channel: ReleaseChannel?) {
        val editor = preferences.edit()
        val preferenceKey = key(sourceKey, owner, repo)
        if (channel == null) {
            editor.remove(preferenceKey)
        } else {
            editor.putString(preferenceKey, channel.key)
        }
        editor.apply()
    }

    fun set(info: AppInfo, channel: ReleaseChannel?) =
        set(info.sourceKey, info.owner, info.repo, channel)

    private fun key(sourceKey: String, owner: String, repo: String): String =
        "${sourceKey.trim()}/${owner.trim()}/${repo.trim()}".lowercase(Locale.US)
}
