package com.sysadmin.lasstore.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sysadmin.lasstore.ui.catalog.CatalogExperience
import com.sysadmin.lasstore.ui.log.LogScreen
import com.sysadmin.lasstore.ui.settings.SettingsScreen
import com.sysadmin.lasstore.ui.theme.Catppuccin

private const val ROUTE_CATALOG = "catalog"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_LOG = "log"

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    Scaffold(
        containerColor = Catppuccin.Crust,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            StoreDock(
                catalogSelected = current?.hierarchy?.any { it.route == ROUTE_CATALOG } == true,
                settingsSelected = current?.hierarchy?.any { it.route == ROUTE_SETTINGS } == true,
                activitySelected = current?.hierarchy?.any { it.route == ROUTE_LOG } == true,
                onCatalog = {
                    nav.navigate(ROUTE_CATALOG) {
                        popUpTo(nav.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onSettings = { nav.navigate(ROUTE_SETTINGS) { launchSingleTop = true } },
                onActivity = { nav.navigate(ROUTE_LOG) { launchSingleTop = true } },
            )
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = ROUTE_CATALOG,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable(ROUTE_CATALOG) { CatalogExperience() }
            composable(ROUTE_SETTINGS) { SettingsScreen() }
            composable(ROUTE_LOG) { LogScreen() }
        }
    }
}

@Composable
private fun StoreDock(
    catalogSelected: Boolean,
    settingsSelected: Boolean,
    activitySelected: Boolean,
    onCatalog: () -> Unit,
    onSettings: () -> Unit,
    onActivity: () -> Unit,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Catppuccin.MauveStrong,
        selectedTextColor = Catppuccin.MauveStrong,
        indicatorColor = Catppuccin.Surface2,
        unselectedIconColor = Catppuccin.Subtext,
        unselectedTextColor = Catppuccin.Subtext,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Catppuccin.Crust)
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            color = Catppuccin.PanelRaised,
            border = BorderStroke(1.dp, Catppuccin.Stroke),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                NavigationBarItem(
                    selected = catalogSelected,
                    onClick = onCatalog,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Apps, contentDescription = null) },
                    label = { Text("Catalog") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = settingsSelected,
                    onClick = onSettings,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = activitySelected,
                    onClick = onActivity,
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Activity") },
                    colors = itemColors,
                )
            }
        }
    }
}
