package com.sysadmin.lasstore.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

object WearProtocol {
    const val PHONE_CAPABILITY = "local_android_store_phone"
    const val UPDATE_COUNT_PATH = "/local-android-store/update-count"
    const val REQUEST_REFRESH_PATH = "/local-android-store/request-refresh"

    fun encodeUpdateCount(count: Int): ByteArray =
        count.coerceAtLeast(0).toString().toByteArray(Charsets.UTF_8)
}

class WearUpdateMessenger(
    context: Context,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val appContext = context.applicationContext

    fun registerPhoneCapability() {
        Wearable.getCapabilityClient(appContext)
            .addLocalCapability(WearProtocol.PHONE_CAPABILITY)
            .addOnFailureListener(onFailure)
    }

    fun publishUpdateCount(count: Int) {
        val payload = WearProtocol.encodeUpdateCount(count)
        Wearable.getNodeClient(appContext).connectedNodes
            .addOnSuccessListener { nodes ->
                nodes.forEach { node ->
                    Wearable.getMessageClient(appContext)
                        .sendMessage(node.id, WearProtocol.UPDATE_COUNT_PATH, payload)
                        .addOnFailureListener(onFailure)
                }
            }
            .addOnFailureListener(onFailure)
    }
}
