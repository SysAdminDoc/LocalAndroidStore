package com.sysadmin.lasstore

import android.app.Application
import com.sysadmin.lasstore.data.ServiceLocator

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
