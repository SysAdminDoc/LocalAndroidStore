package com.sysadmin.lasstore.wear

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

class StoreMessageService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearProtocol.UPDATE_COUNT_PATH) return
        val count = WearProtocol.decodeUpdateCount(messageEvent.data) ?: return
        getSharedPreferences("wear_update_state", MODE_PRIVATE)
            .edit()
            .putInt("update_count", count)
            .apply()
        TileService.getUpdater(this).requestUpdate(StoreTileService::class.java)
    }
}
