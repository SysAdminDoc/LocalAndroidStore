package com.sysadmin.lasstore

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.sysadmin.lasstore.ui.AppRoot
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result is informational; we don't gate any UI on it */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationsPermission()
        setContent {
            LocalAndroidStoreTheme {
                AppRoot()
            }
        }
    }

    /**
     * Android 13+ requires runtime permission for POST_NOTIFICATIONS. We use the channel
     * for "update available" + "install complete" notifications when scheduled-update Workers
     * land in v0.4. Asking now means the permission is in place by the time we need it.
     */
    private fun maybeRequestNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
