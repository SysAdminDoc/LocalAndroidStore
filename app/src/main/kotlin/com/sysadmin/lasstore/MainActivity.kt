package com.sysadmin.lasstore

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.sysadmin.lasstore.data.AppSettings
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.ui.AppRoot
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme

class MainActivity : ComponentActivity() {

    private var notificationPermissionResult: ((Boolean) -> Unit)? = null
    private val activityResumed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val callback = notificationPermissionResult
        notificationPermissionResult = null
        callback?.invoke(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            val appSettings by ServiceLocator.settings.flow.collectAsState(initial = AppSettings())
            LocalAndroidStoreTheme(appSettings = appSettings) {
                AppRoot(
                    requestNotificationPermission = ::requestNotificationPermission,
                    openNotificationSettings = ::openNotificationSettings,
                    activityResumed = activityResumed,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed.tryEmit(Unit)
    }

    private fun requestNotificationPermission(callback: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            callback(true)
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            callback(true)
        } else {
            notificationPermissionResult = callback
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openNotificationSettings() {
        startActivity(
            android.content.Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }
}
