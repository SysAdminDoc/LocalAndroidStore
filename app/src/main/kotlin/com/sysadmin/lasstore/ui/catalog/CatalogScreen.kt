package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun CatalogScreen(viewModel: CatalogViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(Catppuccin.Crust)) {
        TopHeader(
            refreshing = state.refreshing,
            onRefresh = {
                viewModel.refreshInstallPermission()
                viewModel.refresh()
            },
        )

        if (!state.canRequestInstalls) {
            PermissionBanner(onClick = { viewModel.openInstallPermissionSettings() })
        }
        state.warning?.let {
            WarningBanner(text = it, onDismiss = { viewModel.dismissWarning() })
        }

        when {
            state.refreshing && state.cards.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = Catppuccin.Mauve) }
            }
            state.cards.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No releases found yet.\nOpen Settings to configure your GitHub user, then refresh.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Catppuccin.Subtext,
                    )
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 320.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.cards, key = { "${it.info.owner}/${it.info.repo}" }) { card ->
                        AppCard(
                            state = card,
                            onInstall = { viewModel.install(card) },
                            onUpdate = { viewModel.install(card) },
                            onUninstall = { viewModel.uninstall(card) },
                            onOpen = { viewModel.open(card) },
                            onRepo = { viewModel.openRepo(card) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopHeader(refreshing: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Catppuccin.Crust)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "LocalAndroidStore",
                style = MaterialTheme.typography.titleLarge,
                color = Catppuccin.Mauve,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Your apps. Your repos. One tap.",
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
            )
        }
        IconButton(
            onClick = onRefresh,
            enabled = !refreshing,
        ) {
            if (refreshing) {
                CircularProgressIndicator(
                    color = Catppuccin.Mauve,
                    strokeWidth = 2.dp,
                    modifier = Modifier.padding(4.dp),
                )
            } else {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Catppuccin.Mauve)
            }
        }
    }
}

@Composable
private fun PermissionBanner(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Catppuccin.Surface0, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = Catppuccin.Yellow)
        Spacer(Modifier.height(0.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = "Install permission required",
                color = Catppuccin.Yellow,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Android needs your OK to let LocalAndroidStore install APKs.",
                color = Catppuccin.Subtext,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = onClick) { Text("Grant") }
    }
}

@Composable
private fun WarningBanner(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(Catppuccin.Surface0, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Catppuccin.Red,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onDismiss) { Text("Dismiss") }
    }
}
