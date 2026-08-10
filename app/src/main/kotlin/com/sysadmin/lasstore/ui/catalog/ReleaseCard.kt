package com.sysadmin.lasstore.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.data.normalizeSha256Digest
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.time.Instant

@Composable
fun ReleaseCard(
    state: CardState,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onQueueUpdate: () -> Unit,
    onCancelQueuedUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onOpen: () -> Unit,
    onRepo: () -> Unit,
    onCancel: () -> Unit,
    onProceedPermissions: () -> Unit,
    onCancelPermissions: () -> Unit,
    onIgnore: () -> Unit,
    onSaveApk: () -> Unit,
    onReplacePublisherPin: (typedApplicationId: String, independentlyVerified: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isStale = remember(state.info.publishedAt) {
        val publishedAt = state.info.publishedAt ?: return@remember false
        val published = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrNull()
            ?: return@remember false
        published < System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    }
    var notesExpanded by rememberSaveable(state.info.handle) { mutableStateOf(false) }
    var trustRecoveryVisible by rememberSaveable(
        state.info.handle,
        state.publisherTrustDetails?.downloadedMetadata?.signingSha256,
    ) {
        mutableStateOf(false)
    }
    val cardShape = RoundedCornerShape(24.dp)

    if (trustRecoveryVisible) {
        state.publisherTrustDetails?.let { details ->
            PublisherTrustRecoveryDialog(
                details = details,
                onDismiss = { trustRecoveryVisible = false },
                onConfirm = { typedApplicationId, independentlyVerified ->
                    trustRecoveryVisible = false
                    onReplacePublisherPin(typedApplicationId, independentlyVerified)
                },
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        color = Catppuccin.Panel,
        border = BorderStroke(1.dp, Catppuccin.StrokeBright.copy(alpha = 0.75f)),
    ) {
        Box(
            modifier = Modifier.background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Catppuccin.Surface1.copy(alpha = 0.72f),
                        Catppuccin.Panel,
                        Catppuccin.PanelRaised.copy(alpha = 0.86f),
                    ),
                ),
            ),
        ) {
            Column(
                modifier = Modifier.padding(17.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppMonogram(
                        name = state.info.displayName,
                        seed = state.info.handle,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.info.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = Catppuccin.TextStrong,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = Catppuccin.Subtext,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = if (state.info.sourceLabel == state.info.owner) {
                                    state.info.handle
                                } else {
                                    "${state.info.sourceLabel} · ${state.info.handle}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Subtext,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    ReleaseOverflowMenu(
                        state = state,
                        onQueueUpdate = onQueueUpdate,
                        onCancelQueuedUpdate = onCancelQueuedUpdate,
                        onOpen = onOpen,
                        onRepo = onRepo,
                        onCancelPermissions = onCancelPermissions,
                        onIgnore = onIgnore,
                        onSaveApk = onSaveApk,
                        onUninstall = onUninstall,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MetaPill(
                        label = state.info.tagName,
                        accent = Catppuccin.Sapphire,
                    )
                    StatusBadge(status = state.status)
                    state.info.channelLabel?.let { channel ->
                        MetaPill(
                            label = channel.uppercase(),
                            accent = Catppuccin.Peach,
                        )
                    }
                }

                Text(
                    text = state.info.description ?: "No release description provided.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                if (normalizeSha256Digest(state.info.asset.digest) == null) {
                    Text(
                        text = "Integrity unavailable · GitHub did not publish a SHA-256 digest; " +
                            "background updates are disabled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Peach,
                    )
                }

                if (
                    state.installedVersion != null &&
                    state.info.versionCode != null &&
                    state.installedVersion != state.info.versionName
                ) {
                    Text(
                        text = "Installed ${state.installedVersion}  →  Release " +
                            "${state.info.versionName ?: state.info.tagName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Sapphire,
                    )
                } else if (
                    state.installedVersion != null &&
                    state.status == CardStatus.ReleaseAvailable
                ) {
                    Text(
                        text = "Installed ${state.installedVersion}  •  " +
                            "Tag ${state.info.tagName} not inspected",
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                    )
                }

                if (state.status == CardStatus.Working) {
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp),
                        color = Catppuccin.MauveStrong,
                        trackColor = Catppuccin.Surface2,
                    )
                }

                state.message?.let { message ->
                    val messageColor = when (state.status) {
                        CardStatus.Error, CardStatus.SignatureMismatch -> Catppuccin.Red
                        else -> Catppuccin.Subtext
                    }
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = messageColor,
                    )
                }

                state.developerVerificationNotice?.let { notice ->
                    VerificationNotice(
                        title = notice.title,
                        body = notice.body,
                        guidanceUrl = notice.guidanceUrl,
                    )
                }

                if (state.newDangerousPermissions.isNotEmpty()) {
                    PermissionReview(
                        permissions = state.newDangerousPermissions,
                        onCancel = onCancelPermissions,
                    )
                }

                if (state.info.releaseBody != null) {
                    TextButton(
                        onClick = { notesExpanded = !notesExpanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = if (notesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (notesExpanded) "Hide release notes" else "What’s new")
                    }
                    AnimatedVisibility(visible = notesExpanded) {
                        Text(
                            text = state.info.releaseBody,
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Subtext,
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Catppuccin.Crust.copy(alpha = 0.58f), RoundedCornerShape(14.dp))
                                .border(
                                    width = 1.dp,
                                    color = Catppuccin.Stroke,
                                    shape = RoundedCornerShape(14.dp),
                                )
                                .padding(12.dp),
                        )
                    }
                }

                HorizontalDivider(color = Catppuccin.Stroke)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.StarBorder,
                                contentDescription = null,
                                tint = Catppuccin.Subtext,
                                modifier = Modifier.size(17.dp),
                            )
                            Text(
                                text = state.info.stars.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                color = Catppuccin.Subtext,
                            )
                            Text(
                                text = "·",
                                color = Catppuccin.Overlay,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = state.info.sourceLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = Catppuccin.Subtext,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (isStale) {
                            Text(
                                text = "No release in the past year",
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Peach,
                                maxLines = 1,
                            )
                        } else if (state.isIgnored) {
                            Text(
                                text = "Update alerts muted",
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Overlay,
                                maxLines = 1,
                            )
                        }
                    }

                    PrimaryReleaseAction(
                        status = state.status,
                        onInstall = onInstall,
                        onUpdate = onUpdate,
                        onOpen = onOpen,
                        onCancel = onCancel,
                        onProceedPermissions = onProceedPermissions,
                        onReviewTrust = state.publisherTrustDetails?.let {
                            { trustRecoveryVisible = true }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppMonogram(
    name: String,
    seed: String,
) {
    val palette = remember(seed) {
        when ((seed.hashCode() and Int.MAX_VALUE) % 4) {
            0 -> Triple(Catppuccin.MauveStrong, Color(0xFF4A2873), Catppuccin.MauveStrong)
            1 -> Triple(Catppuccin.Sapphire, Color(0xFF203F78), Catppuccin.Sapphire)
            2 -> Triple(Catppuccin.Mint, Color(0xFF194E42), Catppuccin.Mint)
            else -> Triple(Catppuccin.Peach, Color(0xFF63352E), Catppuccin.Peach)
        }
    }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .size(58.dp)
            .background(
                brush = Brush.linearGradient(listOf(palette.first, palette.second)),
                shape = shape,
            )
            .border(1.dp, palette.third.copy(alpha = 0.42f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "A",
            style = MaterialTheme.typography.headlineMedium,
            color = Catppuccin.TextStrong,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun MetaPill(
    label: String,
    accent: Color,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.09f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.48f)),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = accent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun PrimaryReleaseAction(
    status: CardStatus,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onOpen: () -> Unit,
    onCancel: () -> Unit,
    onProceedPermissions: () -> Unit,
    onReviewTrust: (() -> Unit)?,
) {
    val modifier = Modifier
        .widthIn(min = 122.dp)
        .height(48.dp)

    when (status) {
        CardStatus.NotInstalled -> {
            AccentButton(
                text = "Install",
                icon = Icons.Default.Download,
                accent = Catppuccin.MauveStrong,
                onClick = onInstall,
                modifier = modifier,
            )
        }
        CardStatus.ReleaseAvailable -> {
            AccentButton(
                text = "Check release",
                icon = Icons.Default.Refresh,
                accent = Catppuccin.Sapphire,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.UpdateAvailable -> {
            AccentButton(
                text = "Update",
                icon = Icons.Default.SystemUpdateAlt,
                accent = Catppuccin.MauveStrong,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.ReinstallAvailable -> {
            AccentButton(
                text = "Reinstall",
                icon = Icons.Default.Download,
                accent = Catppuccin.Sapphire,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.DowngradeAvailable -> {
            AccentButton(
                text = "Downgrade",
                icon = Icons.Default.Warning,
                accent = Catppuccin.Peach,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.Installed -> {
            AccentButton(
                text = "Open",
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                accent = Catppuccin.Mint,
                onClick = onOpen,
                modifier = modifier,
            )
        }
        CardStatus.Working -> {
            OutlinedButton(
                onClick = onCancel,
                modifier = modifier,
                border = BorderStroke(1.dp, Catppuccin.Red.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.Red),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(7.dp))
                Text("Cancel")
            }
        }
        CardStatus.Error -> {
            AccentButton(
                text = "Retry",
                icon = Icons.Default.Refresh,
                accent = Catppuccin.MauveStrong,
                onClick = onInstall,
                modifier = modifier,
            )
        }
        CardStatus.SignatureMismatch -> {
            if (onReviewTrust != null) {
                AccentButton(
                    text = "Review trust",
                    icon = Icons.Default.Lock,
                    accent = Catppuccin.Red,
                    onClick = onReviewTrust,
                    modifier = modifier,
                )
            } else {
                Button(
                    onClick = {},
                    enabled = false,
                    modifier = modifier,
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = Catppuccin.Red.copy(alpha = 0.12f),
                        disabledContentColor = Catppuccin.Red,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text("Blocked")
                }
            }
        }
        CardStatus.PermissionReview -> {
            AccentButton(
                text = "Install",
                icon = Icons.Default.Security,
                accent = Catppuccin.Peach,
                onClick = onProceedPermissions,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun AccentButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(
            containerColor = accent,
            contentColor = Catppuccin.Crust,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(text)
    }
}

@Composable
private fun ReleaseOverflowMenu(
    state: CardState,
    onQueueUpdate: () -> Unit,
    onCancelQueuedUpdate: () -> Unit,
    onOpen: () -> Unit,
    onRepo: () -> Unit,
    onCancelPermissions: () -> Unit,
    onIgnore: () -> Unit,
    onSaveApk: () -> Unit,
    onUninstall: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "More actions for ${state.info.displayName}",
                tint = Catppuccin.Subtext,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = Catppuccin.PanelRaised,
            border = BorderStroke(1.dp, Catppuccin.Stroke),
        ) {
            if (state.status.hasInstalledApp()) {
                ReleaseMenuItem(
                    text = "Open app",
                    icon = Icons.Default.PlayArrow,
                    onClick = {
                        expanded = false
                        onOpen()
                    },
                )
            }
            if (state.status == CardStatus.UpdateAvailable &&
                state.queuedUpdateStatus?.isPending != true
            ) {
                ReleaseMenuItem(
                    text = "Queue update",
                    icon = Icons.Default.Schedule,
                    onClick = {
                        expanded = false
                        onQueueUpdate()
                    },
                )
            }
            if (state.queuedUpdateStatus?.isPending == true) {
                ReleaseMenuItem(
                    text = "Cancel queued update",
                    icon = Icons.Default.Close,
                    onClick = {
                        expanded = false
                        onCancelQueuedUpdate()
                    },
                    tint = Catppuccin.Red,
                )
            }
            if (state.status.hasInstalledApp()) {
                ReleaseMenuItem(
                    text = if (state.isIgnored) "Unmute update alerts" else "Mute this update",
                    icon = if (state.isIgnored) {
                        Icons.Default.NotificationsActive
                    } else {
                        Icons.Default.NotificationsOff
                    },
                    onClick = {
                        expanded = false
                        onIgnore()
                    },
                )
            }
            if (state.status == CardStatus.PermissionReview) {
                ReleaseMenuItem(
                    text = "Cancel permission review",
                    icon = Icons.Default.Close,
                    onClick = {
                        expanded = false
                        onCancelPermissions()
                    },
                )
            }
            if (state.status != CardStatus.Working) {
                ReleaseMenuItem(
                    text = "Save APK",
                    icon = Icons.Default.SaveAlt,
                    onClick = {
                        expanded = false
                        onSaveApk()
                    },
                )
            }
            ReleaseMenuItem(
                text = "Open repository",
                icon = Icons.Default.Code,
                onClick = {
                    expanded = false
                    onRepo()
                },
            )
            if (state.status.hasInstalledApp()) {
                ReleaseMenuItem(
                    text = "App details & uninstall",
                    icon = Icons.Default.Info,
                    onClick = {
                        expanded = false
                        onUninstall()
                    },
                    tint = Catppuccin.Red,
                )
            }
        }
    }
}

private fun CardStatus.hasInstalledApp(): Boolean = this in setOf(
    CardStatus.Installed,
    CardStatus.ReleaseAvailable,
    CardStatus.UpdateAvailable,
    CardStatus.ReinstallAvailable,
    CardStatus.DowngradeAvailable,
)

@Composable
private fun ReleaseMenuItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    tint: Color = Catppuccin.Subtext,
) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tint == Catppuccin.Red) Catppuccin.Red else Catppuccin.Text,
            )
        },
        onClick = onClick,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(19.dp),
            )
        },
    )
}

@Composable
private fun PermissionReview(
    permissions: List<String>,
    onCancel: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.Peach.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Catppuccin.Peach.copy(alpha = 0.32f)),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Catppuccin.Peach,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "New dangerous permissions",
                    style = MaterialTheme.typography.titleSmall,
                    color = Catppuccin.Peach,
                )
            }
            permissions.forEach { permission ->
                Text(
                    text = "• ${permission.removePrefix("android.permission.")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
            }
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text("Cancel review", color = Catppuccin.Red)
            }
        }
    }
}

@Composable
private fun VerificationNotice(
    title: String,
    body: String,
    guidanceUrl: String,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.Yellow.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, Catppuccin.Yellow.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = Catppuccin.Yellow,
                modifier = Modifier.size(19.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Catppuccin.Yellow,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
                TextButton(
                    onClick = { uriHandler.openUri(guidanceUrl) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                ) {
                    Text("Official Android guidance")
                }
            }
        }
    }
}
