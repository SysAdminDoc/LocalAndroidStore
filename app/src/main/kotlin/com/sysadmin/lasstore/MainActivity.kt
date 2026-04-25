package com.sysadmin.lasstore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sysadmin.lasstore.ui.AppRoot
import com.sysadmin.lasstore.ui.theme.LocalAndroidStoreTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAndroidStoreTheme {
                AppRoot()
            }
        }
    }
}
