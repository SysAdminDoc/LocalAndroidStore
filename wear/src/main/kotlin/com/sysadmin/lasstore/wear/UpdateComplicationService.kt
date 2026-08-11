package com.sysadmin.lasstore.wear

import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.EmptyComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService.ComplicationRequestListener

class UpdateComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener,
    ) {
        if (request.complicationType != ComplicationType.SHORT_TEXT) {
            listener.onComplicationData(EmptyComplicationData())
            return
        }
        listener.onComplicationData(shortTextData(updateCount()))
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData =
        if (type == ComplicationType.SHORT_TEXT) {
            shortTextData(0)
        } else {
            NoDataComplicationData()
        }

    private fun updateCount(): Int = getSharedPreferences("wear_update_state", MODE_PRIVATE)
        .getInt("update_count", 0)
        .coerceAtLeast(0)

    private fun shortTextData(count: Int): ShortTextComplicationData {
        val text = PlainComplicationText.Builder(count.toString()).build()
        val description = PlainComplicationText.Builder(
            "$count LocalAndroidStore updates available",
        ).build()
        return ShortTextComplicationData.Builder(text, description).build()
    }
}
