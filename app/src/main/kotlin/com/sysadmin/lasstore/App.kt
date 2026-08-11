package com.sysadmin.lasstore

import android.app.Application
import com.sysadmin.lasstore.data.ServiceLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
        runCatching {
            ServiceLocator.backgroundUpdates.schedulePeriodicCheck()
        }.onFailure {
            ServiceLocator.logger.warn(
                "PeriodicUpdate",
                "Could not schedule the periodic catalog check: ${it.message}",
            )
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                ServiceLocator.settings.recoverPendingTransaction()
            } catch (throwable: Throwable) {
                ServiceLocator.logger.error(
                    "Settings",
                    "Could not recover an interrupted source registry save",
                    throwable,
                )
            }
            ServiceLocator.backgroundUpdates.reconcilePersistedWork()
        }
    }
}
