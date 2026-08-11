package com.sysadmin.lasstore.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class QueuedUpdateBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                com.sysadmin.lasstore.data.ServiceLocator.init(context.applicationContext)
                com.sysadmin.lasstore.data.ServiceLocator.backgroundUpdates.reconcilePersistedWork()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
