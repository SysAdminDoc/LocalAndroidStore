package com.sysadmin.lasstore.wear

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.tiles.LayoutElementBuilders
import androidx.wear.tiles.ActionBuilders
import androidx.wear.tiles.ModifiersBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.TimelineBuilders
import com.google.common.util.concurrent.ListenableFuture

private const val PREFERENCES = "wear_update_state"
private const val KEY_UPDATE_COUNT = "update_count"

@Suppress("DEPRECATION")
class StoreTileService : TileService() {
    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> = CallbackToFutureAdapter.getFuture { completer ->
        val updates = updateStore(this).getUpdates()
        completer.set(buildTile(updates))
        null
    }

    private fun buildTile(updateCount: Int): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(15L * 60L * 1_000L)
            .setTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(
                                        LayoutElementBuilders.Column.Builder()
                                            .addContent(
                                                LayoutElementBuilders.Text.Builder()
                                                    .setText("LocalAndroidStore")
                                                    .build(),
                                            )
                                            .addContent(
                                                LayoutElementBuilders.Text.Builder()
                                                    .setText("$updateCount updates available")
                                                    .build(),
                                            )
                                            .setModifiers(
                                                ModifiersBuilders.Modifiers.Builder()
                                                    .setClickable(
                                                        ModifiersBuilders.Clickable.Builder()
                                                            .setOnClick(
                                                                ActionBuilders.LaunchAction.Builder()
                                                                    .setAndroidActivity(
                                                                        ActionBuilders.AndroidActivity.Builder()
                                                                            .setClassName(
                                                                                RequestRefreshActivity::class.java.name,
                                                                            )
                                                                            .setPackageName(packageName)
                                                                            .build(),
                                                                    )
                                                                    .build(),
                                                            )
                                                            .build(),
                                                    )
                                                    .build(),
                                            )
                                            .build(),
                                    )
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()

    private companion object {
        const val RESOURCES_VERSION = "1"

        fun updateStore(context: Context): UpdateCountStore = UpdateCountStore(context)
    }
}

private class UpdateCountStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun getUpdates(): Int = preferences.getInt(KEY_UPDATE_COUNT, 0).coerceAtLeast(0)
}
