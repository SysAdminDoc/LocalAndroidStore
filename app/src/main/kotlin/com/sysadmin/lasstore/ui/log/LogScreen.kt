package com.sysadmin.lasstore.ui.log

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmin.lasstore.data.InstallAuditLog
import com.sysadmin.lasstore.data.LogEntry
import com.sysadmin.lasstore.data.LogLevel
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.SupportBundleExporter
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class JournalCategory(
    val label: String,
    val description: String,
) {
    Diagnostics(
        label = "Diagnostics",
        description = "Recent runtime, network, and workflow events.",
    ),
    InstallAudit(
        label = "Install audit",
        description = "Durable install, uninstall, and trust decisions.",
    ),
    CrashEvidence(
        label = "Crash evidence",
        description = "Durable handled and uncaught failure details.",
    ),
}

@Composable
fun LogScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val diagnostics by ServiceLocator.logger.entries.collectAsStateWithLifecycle()
    val auditEntries by ServiceLocator.audit.entries.collectAsStateWithLifecycle()
    val crashEntries by ServiceLocator.logger.crashEntries.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf(JournalCategory.Diagnostics) }
    var showClearConfirmation by rememberSaveable { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val entries = when (category) {
        JournalCategory.Diagnostics -> diagnostics
        JournalCategory.InstallAudit -> auditEntries.map(InstallAuditLog.Entry::asLogEntry)
        JournalCategory.CrashEvidence -> crashEntries
    }
    val counts = mapOf(
        JournalCategory.Diagnostics to diagnostics.size,
        JournalCategory.InstallAudit to auditEntries.size,
        JournalCategory.CrashEvidence to crashEntries.size,
    )
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }
    val warningCount = entries.count { it.level == LogLevel.Warn }
    val errorCount = entries.count { it.level == LogLevel.Error }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Catppuccin.Crust),
    ) {
        ActivityHeader(
            hasEntries = entries.isNotEmpty(),
            exporting = exporting,
            onExport = {
                if (!exporting) {
                    exporting = true
                    exportStatus = null
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.IO) {
                                val exporter = SupportBundleExporter(context)
                                val bundle = exporter.create()
                                Intent.createChooser(
                                    exporter.shareIntent(bundle),
                                    "Share redacted support bundle",
                                )
                            }
                        }.onSuccess { chooser ->
                            context.startActivity(chooser)
                            exportStatus = "Redacted support bundle is ready to share."
                        }.onFailure {
                            exportStatus = "Support export failed: ${it.message ?: "unknown error"}"
                        }
                        exporting = false
                    }
                }
            },
            onClear = { showClearConfirmation = true },
        )

        JournalTabs(
            selected = category,
            counts = counts,
            onSelected = {
                category = it
                exportStatus = null
            },
        )

        Text(
            text = category.description,
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Subtext,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )

        exportStatus?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("Support export failed")) {
                    Catppuccin.Red
                } else {
                    Catppuccin.Green
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        ActivityMetrics(
            total = entries.size,
            warningCount = warningCount,
            errorCount = errorCount,
        )

        if (entries.isEmpty()) {
            EmptyActivity(category)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 6.dp,
                    end = 16.dp,
                    bottom = 20.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(
                    items = entries.reversed(),
                    key = { "${category.name}-${it.ts}-${it.tag}-${it.message.hashCode()}" },
                ) { entry ->
                    ActivityEntry(
                        entry = entry,
                        formattedTime = timeFormat.format(Date(entry.ts)),
                    )
                }
            }
        }
    }

    if (showClearConfirmation) {
        val copy = category.clearCopy()
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(copy.first) },
            text = { Text(copy.second) },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (category) {
                            JournalCategory.Diagnostics ->
                                ServiceLocator.logger.clearDiagnostics()
                            JournalCategory.InstallAudit ->
                                ServiceLocator.audit.clear()
                            JournalCategory.CrashEvidence ->
                                ServiceLocator.logger.clearCrashEvidence()
                        }
                        showClearConfirmation = false
                    },
                ) {
                    Text(copy.first)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun ActivityHeader(
    hasEntries: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 14.dp, end = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "DEVICE JOURNAL",
                style = MaterialTheme.typography.labelSmall,
                color = Catppuccin.MauveStrong,
            )
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = Catppuccin.MauveStrong,
                modifier = Modifier.size(14.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Catppuccin.TextStrong,
                )
                Text(
                    text = "Inspect local evidence or export a redacted support bundle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                )
            }
            IconButton(
                onClick = onExport,
                enabled = !exporting,
            ) {
                if (exporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Export redacted support bundle",
                        tint = Catppuccin.Sapphire,
                    )
                }
            }
            if (hasEntries) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear selected journal",
                        tint = Catppuccin.Subtext,
                    )
                }
            }
        }
    }
}

@Composable
private fun JournalTabs(
    selected: JournalCategory,
    counts: Map<JournalCategory, Int>,
    onSelected: (JournalCategory) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        JournalCategory.entries.forEach { category ->
            FilterChip(
                selected = selected == category,
                onClick = { onSelected(category) },
                label = { Text("${category.label} ${counts[category] ?: 0}") },
            )
        }
    }
}

@Composable
private fun ActivityMetrics(
    total: Int,
    warningCount: Int,
    errorCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityMetric(
            label = "Events",
            value = total,
            icon = Icons.AutoMirrored.Filled.ReceiptLong,
            accent = Catppuccin.Sapphire,
            modifier = Modifier.weight(1f),
        )
        ActivityMetric(
            label = "Warnings",
            value = warningCount,
            icon = Icons.Default.Warning,
            accent = Catppuccin.Yellow,
            modifier = Modifier.weight(1f),
        )
        ActivityMetric(
            label = "Errors",
            value = errorCount,
            icon = Icons.Default.Error,
            accent = Catppuccin.Red,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActivityMetric(
    label: String,
    value: Int,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.PanelRaised,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            Column {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    color = accent,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun EmptyActivity(category: JournalCategory) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(Catppuccin.Surface1, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = Catppuccin.MauveStrong,
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(
                text = when (category) {
                    JournalCategory.Diagnostics -> "No diagnostics recorded"
                    JournalCategory.InstallAudit -> "No install decisions recorded"
                    JournalCategory.CrashEvidence -> "No crash evidence recorded"
                },
                style = MaterialTheme.typography.titleLarge,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = category.description,
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
            )
        }
    }
}

@Composable
private fun ActivityEntry(
    entry: LogEntry,
    formattedTime: String,
) {
    var expanded by rememberSaveable(entry.ts, entry.tag) { mutableStateOf(false) }
    val accent = when (entry.level) {
        LogLevel.Info -> Catppuccin.Sapphire
        LogLevel.Warn -> Catppuccin.Yellow
        LogLevel.Error -> Catppuccin.Red
    }
    val icon = when (entry.level) {
        LogLevel.Info -> Icons.Default.CheckCircle
        LogLevel.Warn -> Icons.Default.Warning
        LogLevel.Error -> Icons.Default.Error
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.PanelRaised,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accent.copy(alpha = 0.11f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = accent.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = entry.level.name.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text = entry.tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = Catppuccin.TextStrong,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = Catppuccin.Overlay,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                    fontFamily = FontFamily.Monospace,
                    maxLines = if (expanded) Int.MAX_VALUE else 7,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.message.length > 320) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            imageVector = if (expanded) {
                                Icons.Default.ExpandLess
                            } else {
                                Icons.Default.ExpandMore
                            },
                            contentDescription = null,
                            modifier = Modifier.size(17.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (expanded) "Collapse details" else "Show technical details")
                    }
                }
            }
        }
    }
}

private fun InstallAuditLog.Entry.asLogEntry(): LogEntry = LogEntry(
    ts = ts,
    level = when (event) {
        "install_failed" -> LogLevel.Error
        "install_blocked",
        "developer_verification_warned",
        "publisher_pin_recovery_authorized",
        -> LogLevel.Warn
        else -> LogLevel.Info
    },
    tag = applicationId.ifBlank { source.ifBlank { "Install" } },
    message = buildString {
        append(event.replace('_', ' '))
        if (source.isNotBlank()) append("\nSource: $source")
        if (tagName.isNotBlank()) append("\nRelease: $tagName")
        if (!versionName.isNullOrBlank() || versionCode != null) {
            append("\nVersion: ${versionName.orEmpty()} (${versionCode ?: "unknown"})")
        }
        if (reason.isNotBlank()) append("\nReason: $reason")
        if (message.isNotBlank()) append("\nMessage: $message")
        if (certSha256.isNotBlank()) append("\nSigner: $certSha256")
        if (previousCertSha256.isNotBlank()) append("\nPrevious signer: $previousCertSha256")
        if (installedCertSha256.isNotBlank()) append("\nInstalled signer: $installedCertSha256")
        if (verifiedSignatureSchemes.isNotEmpty()) {
            append("\nVerified schemes: ${verifiedSignatureSchemes.joinToString()}")
        }
    },
)

private fun JournalCategory.clearCopy(): Pair<String, String> = when (this) {
    JournalCategory.Diagnostics ->
        "Clear diagnostics" to
            "Remove recent runtime diagnostics from memory and local diagnostic files. " +
            "Install audit and crash evidence stay."
    JournalCategory.InstallAudit ->
        "Clear install audit" to
            "Delete local install, uninstall, and publisher-trust decision history. " +
            "Diagnostics and crash evidence stay."
    JournalCategory.CrashEvidence ->
        "Clear crash evidence" to
            "Delete handled and uncaught failure evidence from local crash files. " +
            "Diagnostics and install audit stay."
}
