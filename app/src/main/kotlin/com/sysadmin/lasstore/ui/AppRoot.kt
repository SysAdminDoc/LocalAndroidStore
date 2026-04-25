package com.sysadmin.lasstore.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sysadmin.lasstore.ui.catalog.CatalogScreen
import com.sysadmin.lasstore.ui.log.LogScreen
import com.sysadmin.lasstore.ui.settings.SettingsScreen

private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LOG = "log"

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = current?.hierarchy?.any { it.route == ROUTE_CATALOG } == true,
                    onClick = {
                        nav.navigate(ROUTE_CATALOG) {
                            popUpTo(nav.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Catalog") },
                )
                NavigationBarItem(
                    selected = current?.hierarchy?.any { it.route == ROUTE_SETTINGS } == true,
                    onClick = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
                NavigationBarItem(
                    selected = current?.hierarchy?.any { it.route == ROUTE_LOG } == true,
                    onClick = { nav.navigate(ROUTE_LOG) { launchSingleTop = true } },
                    icon = { Icon(Icons.Default.Description, contentDescription = null) },
                    label = { Text("Log") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_CATALOG,
            modifier = Modifier.padding(padding),
        ) {
            composable(ROUTE_CATALOG) { CatalogScreen() }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
            composable(ROUTE_LOG) { LogScreen() }
        }
    }
}
