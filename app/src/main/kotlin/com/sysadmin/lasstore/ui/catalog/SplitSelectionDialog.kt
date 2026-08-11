package com.sysadmin.lasstore.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun SplitSelectionDialog(
    state: SplitSelectionState,
    onConfirm: (Set<String>) -> Unit,
    onCancel: () -> Unit,
) {
    var selectedIds by remember(state) {
        mutableStateOf(state.entries.filter { it.selected }.mapTo(mutableSetOf()) { it.id })
    }
    val baseSelected = state.baseId in selectedIds
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.split_selection_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.split_selection_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
                state.entries.forEach { entry ->
                    val checked = entry.id in selectedIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !entry.base) {
                                selectedIds = selectedIds.toMutableSet().also { next ->
                                    if (checked) next.remove(entry.id) else next.add(entry.id)
                                }
                            }
                            .padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = if (entry.base) {
                                null
                            } else {
                                { value ->
                                    selectedIds = selectedIds.toMutableSet().also { next ->
                                        if (value) next.add(entry.id) else next.remove(entry.id)
                                    }
                                }
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.base) {
                                    stringResource(R.string.split_selection_base, entry.displayName)
                                } else {
                                    entry.displayName
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Catppuccin.TextStrong,
                            )
                            Text(
                                text = listOfNotNull(
                                    entry.splitName,
                                    formatSplitSize(entry.sizeBytes),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Subtext,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedIds) },
                enabled = baseSelected,
            ) {
                Text(stringResource(R.string.split_selection_install))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun formatSplitSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> stringResource(
        R.string.asset_size_mib,
        bytes / (1024f * 1024f),
    )
    bytes >= 1024L -> stringResource(R.string.asset_size_kib, bytes / 1024f)
    else -> stringResource(R.string.asset_size_bytes, bytes)
}
