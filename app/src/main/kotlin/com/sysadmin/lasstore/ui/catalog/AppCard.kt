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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.time.Instant

@Composable
fun AppCard(
    state: CardState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
    onRepo: () -> Unit,
    onCancel: () -> Unit,
    onProceedPermissions: () -> Unit = {},
    onCancelPermissions: () -> Unit = {},
    onIgnore: () -> Unit = {},
    onSaveApk: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Stale: last release published more than 12 months ago.
    val isStale = remember(state.info.publishedAt) {
        val publishedAt = state.info.publishedAt ?: return@remember false
        val published = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrNull()
            ?: return@remember false
        published < System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Catppuccin.Surface0),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
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
                        text = if (state.info.sourceLabel == state.info.owner) {
                            state.info.handle
                        } else {
                            "${state.info.sourceLabel} · ${state.info.handle}"
                        },
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

            // Version / channel row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                // Channel label (alpha/beta/rc/nightly/pre) — derived from tag name.
                state.info.channelLabel?.let { label ->
                    Text(
                        text = label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Catppuccin.Crust,
                        modifier = Modifier
                            .background(Catppuccin.Peach, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                // Stale indicator — no release in over a year.
                if (isStale) {
                    Text(
                        text = "stale",
                        style = MaterialTheme.typography.labelSmall,
                        color = Catppuccin.Subtext,
                        modifier = Modifier
                            .background(Catppuccin.Surface2, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
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
                    else -> Catppuccin.Subtext
                }
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }
            state.developerVerificationNotice?.let { notice ->
                DeveloperVerificationNoticeBlock(title = notice.title, body = notice.body)
            }
            if (state.newDangerousPermissions.isNotEmpty()) {
                PermissionDiffBlock(permissions = state.newDangerousPermissions)
            }

            // Release notes — collapsible.
            if (state.info.releaseBody != null) {
                var notesExpanded by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { notesExpanded = !notesExpanded },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        text = if (notesExpanded) "Hide release notes" else "What's new",
                        style = MaterialTheme.typography.labelMedium,
                        color = Catppuccin.Sapphire,
                    )
                    Spacer(Modifier.weight(1f))
                }
                if (notesExpanded) {
                    Text(
                        text = state.info.releaseBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Catppuccin.Surface1, RoundedCornerShape(6.dp))
                            .padding(10.dp),
                    )
                }
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
                        TextButton(onClick = onSaveApk) { Text("Save") }
                    }
                    CardStatus.UpdateAvailable -> {
                        FilledTonalButton(onClick = onUpdate, modifier = Modifier.weight(1f)) {
                            Text("Update")
                        }
                        OutlinedButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Text("Open")
                        }
                        TextButton(onClick = onIgnore) { Text("Ignore") }
                        TextButton(onClick = onSaveApk) { Text("Save") }
                    }
                    CardStatus.Installed -> {
                        FilledTonalButton(onClick = onOpen, modifier = Modifier.weight(1f)) {
                            Text("Open")
                        }
                        OutlinedButton(onClick = onUninstall, modifier = Modifier.weight(1f)) {
                            Text("Uninstall")
                        }
                        if (state.isIgnored) {
                            TextButton(onClick = onIgnore) { Text("Unignore") }
                        }
                        TextButton(onClick = onSaveApk) { Text("Save") }
                    }
                    CardStatus.Working -> {
                        FilledTonalButton(onClick = {}, enabled = false, modifier = Modifier.weight(1f)) {
                            Text("Working…")
                        }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                    }
                    CardStatus.PermissionReview -> {
                        FilledTonalButton(onClick = onProceedPermissions, modifier = Modifier.weight(1f)) {
                            Text("Install anyway")
                        }
                        OutlinedButton(onClick = onCancelPermissions, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }
                if (state.status !in listOf(CardStatus.Working, CardStatus.PermissionReview, CardStatus.UpdateAvailable, CardStatus.Installed, CardStatus.NotInstalled, CardStatus.Error, CardStatus.SignatureMismatch)) {
                    // fallback — never reached with current enum
                }
                TextButton(onClick = onRepo) { Text("Repo") }
            }
        }
    }
}

@Composable
private fun DeveloperVerificationNoticeBlock(title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Catppuccin.Surface1, RoundedCornerShape(8.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Default.Info,
            contentDescription = null,
            tint = Catppuccin.Yellow,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Catppuccin.Yellow,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
        }
    }
}
