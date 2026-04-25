package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun AppCard(
    state: CardState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
    onRepo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Catppuccin.Surface0),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Catppuccin.Surface1),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = state.info.displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Catppuccin.Mauve,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Catppuccin.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = state.info.handle,
                        style = MaterialTheme.typography.labelMedium,
                        color = Catppuccin.Subtext,
                        maxLines = 1,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    StatusBadge(status = state.status)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = Catppuccin.Yellow,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = state.info.stars.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = Catppuccin.Subtext,
                        )
                    }
                }
            }

            Text(
                text = state.info.description ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.info.tagName,
                    style = MaterialTheme.typography.labelLarge,
                    color = Catppuccin.Sapphire,
                )
                if (state.installedVersion != null && state.installedVersion != state.info.versionName) {
                    Text(
                        text = "(installed: ${state.installedVersion})",
                        style = MaterialTheme.typography.labelMedium,
                        color = Catppuccin.Subtext,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (state.info.prerelease) {
                    Text(
                        text = "pre-release",
                        style = MaterialTheme.typography.labelMedium,
                        color = Catppuccin.Peach,
                    )
                }
            }

            if (state.status == CardStatus.Working) {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Catppuccin.Mauve,
                    trackColor = Catppuccin.Surface2,
                )
            }
            if (state.message != null) {
                val color = when (state.status) {
                    CardStatus.Error, CardStatus.SignatureMismatch -> Catppuccin.Red
                    CardStatus.Working -> Catppuccin.Subtext
                    else -> Catppuccin.Subtext
                }
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when (state.status) {
                    CardStatus.NotInstalled, CardStatus.Error, CardStatus.SignatureMismatch -> {
                        FilledTonalButton(
                            onClick = onInstall,
                            enabled = state.status != CardStatus.SignatureMismatch,
                            modifier = Modifier.weight(1f),
                        ) { Text("Install") }
                    }
                    CardStatus.UpdateAvailable -> {
                        FilledTonalButton(onClick = onUpdate, modifier = Modifier.weight(1f)) {
                            Text("Update")
                        }
                        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Text("Open")
                        }
                    }
                    CardStatus.Installed -> {
                        FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Text("Open")
                        }
                        OutlinedButton(onClick = onUninstall, modifier = Modifier.weight(1f)) {
                            Text("Uninstall")
                        }
                    }
                    CardStatus.Working -> {
                        FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                            Text("Working…")
                        }
                    }
                }
                TextButton(onClick = onRepo) { Text("Repo") }
            }
        }
    }
}
