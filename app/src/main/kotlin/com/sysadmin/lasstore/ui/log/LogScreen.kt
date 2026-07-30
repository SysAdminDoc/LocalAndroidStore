package com.sysadmin.lasstore.ui.log

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sysadmin.lasstore.data.LogEntry
import com.sysadmin.lasstore.data.LogLevel
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LogScreen() {
    val entries by ServiceLocator.logger.entries.collectAsStateWithLifecycle()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val warningCount = entries.count { it.level == LogLevel.Warn }
    val errorCount = entries.count { it.level == LogLevel.Error }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Catppuccin.Crust),
    ) {
        ActivityHeader(
            hasEntries = entries.isNotEmpty(),
            onClear = ServiceLocator.logger::clear,
        )

        ActivityMetrics(
            total = entries.size,
            warningCount = warningCount,
            errorCount = errorCount,
        )

        if (entries.isEmpty()) {
            EmptyActivity()
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
                    key = { "${it.ts}-${it.tag}-${it.message.hashCode()}" },
                ) { entry ->
                    ActivityEntry(
                        entry = entry,
                        formattedTime = timeFormat.format(Date(entry.ts)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ActivityHeader(
    hasEntries: Boolean,
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
                    text = "Local install, update, and security events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                )
            }
            if (hasEntries) {
                IconButton(onClick = onClear) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear activity",
                        tint = Catppuccin.Subtext,
                    )
                }
            }
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
private fun EmptyActivity() {
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
                text = "The journal is quiet",
                style = MaterialTheme.typography.titleLarge,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = "Downloads, installs, update checks, and security decisions will appear here.",
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
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
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
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
