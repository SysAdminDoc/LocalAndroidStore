package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.data.ApkSigningBlockReport
import com.sysadmin.lasstore.data.ApkTransparencyReport
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.util.Locale
import androidx.compose.ui.res.stringResource

@Composable
internal fun ApkTransparencyDialog(
    report: ApkTransparencyReport?,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    var manifestExpanded by remember(report?.metadata?.apkSha256) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Security, contentDescription = null, tint = Catppuccin.MauveStrong)
                Text(stringResource(R.string.apk_transparency_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.apk_transparency_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.apk_transparency_loading))
                    }
                }
                error?.let { message ->
                    Text(
                        text = stringResource(R.string.apk_transparency_failed, message),
                        color = Catppuccin.Red,
                    )
                }
                report?.let { transparency ->
                    TransparencyReportContent(
                        report = transparency,
                        manifestExpanded = manifestExpanded,
                        onToggleManifest = { manifestExpanded = !manifestExpanded },
                    )
                }
                if (!busy && report == null && error == null) {
                    Text(stringResource(R.string.apk_transparency_no_local), color = Catppuccin.Subtext)
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (error != null) {
                    TextButton(onClick = onRetry) {
                        Text(stringResource(R.string.apk_transparency_retry))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun TransparencyReportContent(
    report: ApkTransparencyReport,
    manifestExpanded: Boolean,
    onToggleManifest: () -> Unit,
) {
    TransparencySectionTitle(stringResource(R.string.apk_transparency_verified_artifact))
    TransparencyValue(stringResource(R.string.apk_transparency_package), report.metadata.applicationId)
    TransparencyValue(
        stringResource(R.string.apk_transparency_version),
        listOfNotNull(report.metadata.versionName, report.metadata.versionCode.toString()).joinToString(" · "),
    )
    TransparencyValue(
        stringResource(R.string.apk_transparency_apk_sha256),
        report.metadata.apkSha256 ?: stringResource(R.string.unknown),
    )
    TransparencyValue(
        stringResource(R.string.apk_transparency_manifest_sha256),
        report.metadata.manifestSha256 ?: stringResource(R.string.unknown),
    )
    TransparencyValue(stringResource(R.string.apk_transparency_signer), report.metadata.signingSha256)
    TransparencyValue(
        stringResource(R.string.apk_transparency_schemes),
        report.metadata.verifiedSignatureSchemes.joinToString { it.name },
    )
    TransparencyValue(
        stringResource(R.string.apk_transparency_lineage),
        report.metadata.lineageSha256.joinToString().ifBlank {
            stringResource(R.string.apk_transparency_no_lineage)
        },
    )

    HorizontalDivider(color = Catppuccin.Stroke)
    TransparencySectionTitle(stringResource(R.string.apk_transparency_signing_block))
    SigningBlockContent(report.signingBlock)

    HorizontalDivider(color = Catppuccin.Stroke)
    TransparencySectionTitle(stringResource(R.string.apk_transparency_trackers))
    TransparencyValue(
        stringResource(R.string.apk_transparency_tracker_database),
        report.trackerScan.databaseVersion,
    )
    if (report.trackerScan.findings.isEmpty()) {
        Text(
            text = stringResource(R.string.apk_transparency_no_trackers),
            color = Catppuccin.Mint,
        )
    } else {
        Text(
            text = stringResource(
                R.string.apk_transparency_tracker_count,
                report.trackerScan.findings.size,
            ),
            color = Catppuccin.Peach,
        )
        report.trackerScan.findings.forEach { finding ->
            Text(
                text = "• ${finding.name} · ${finding.category}",
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.TextStrong,
            )
        }
    }
    Text(
        text = stringResource(R.string.apk_transparency_tracker_warning),
        style = MaterialTheme.typography.bodySmall,
        color = Catppuccin.Subtext,
    )
    TransparencyValue(
        stringResource(R.string.apk_transparency_scanned_bytes),
        formatBytes(report.trackerScan.scannedBytes),
    )
    if (report.trackerScan.truncated) {
        Text(
            text = stringResource(R.string.apk_transparency_scan_truncated),
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Peach,
        )
    }

    HorizontalDivider(color = Catppuccin.Stroke)
    TextButton(onClick = onToggleManifest, modifier = Modifier.fillMaxWidth()) {
        Icon(
            imageVector = if (manifestExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            stringResource(
                if (manifestExpanded) {
                    R.string.apk_transparency_hide_manifest
                } else {
                    R.string.apk_transparency_show_manifest
                },
            ),
        )
    }
    if (manifestExpanded) {
        SelectionContainer {
            Text(
                text = report.manifestXml,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.TextStrong,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun SigningBlockContent(report: ApkSigningBlockReport) {
    if (!report.present) {
        Text(
            text = report.warning ?: stringResource(R.string.apk_transparency_signing_block_absent),
            color = Catppuccin.Subtext,
        )
        return
    }
    TransparencyValue(
        stringResource(R.string.apk_transparency_signing_block_size),
        formatBytes(report.blockSizeBytes),
    )
    report.entries.forEach { entry ->
        Text(
            text = stringResource(
                R.string.apk_transparency_signing_entry,
                entry.label,
                "0x${entry.id.toString(16)}",
                formatBytes(entry.valueSizeBytes),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.TextStrong,
        )
    }
}

@Composable
private fun TransparencySectionTitle(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Catppuccin.Mint, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = Catppuccin.TextStrong)
    }
}

@Composable
private fun TransparencyValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Catppuccin.Subtext)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.TextStrong,
            overflow = TextOverflow.Clip,
        )
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024L * 1024L -> String.format(Locale.US, "%.1f MiB", value / 1024f / 1024f)
    value >= 1024L -> String.format(Locale.US, "%.1f KiB", value / 1024f)
    else -> "$value bytes"
}
