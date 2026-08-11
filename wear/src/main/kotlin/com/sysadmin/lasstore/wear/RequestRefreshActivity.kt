package com.sysadmin.lasstore.wear

import android.app.Activity
import android.os.Bundle
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable

class RequestRefreshActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Wearable.getCapabilityClient(this)
            .getCapability(WearProtocol.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { capability ->
                capability.nodes.forEach { node ->
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, WearProtocol.REQUEST_REFRESH_PATH, ByteArray(0))
                }
            }
            .addOnCompleteListener { finish() }
    }
}
