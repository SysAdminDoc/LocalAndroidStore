package com.sysadmin.lasstore.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmin.lasstore.data.DEFAULT_GITHUB_TOPIC
import com.sysadmin.lasstore.data.DEFAULT_GITHUB_USER
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.normalizeSources
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var drafts by remember(state.settings.sources, state.sourcePats) {
        mutableStateOf(
            state.settings.sources.map { source ->
                SourceDraft.from(source, state.sourcePats[source.key].orEmpty())
            }
        )
    }

    val normalizedSources = normalizeSources(drafts.map { it.toSource() })
    val sourcePats = drafts
        .mapNotNull { draft ->
            if (draft.user.isBlank()) null else draft.toSource().key to draft.pat
        }
        .distinctBy { it.first }
        .toMap()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Catppuccin.Crust)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleLarge,
            color = Catppuccin.Mauve,
            fontWeight = FontWeight.SemiBold,
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            drafts.forEachIndexed { index, source ->
                SourceEditor(
                    index = index,
                    source = source,
                    canRemove = drafts.size > 1,
                    onChange = { updated ->
                        drafts = drafts.toMutableList().also { it[index] = updated }
                    },
                    onRemove = {
                        drafts = drafts.toMutableList().also { it.removeAt(index) }.ifEmpty {
                            mutableListOf(SourceDraft())
                        }
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { drafts = drafts + SourceDraft(user = "") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Add GitHub source", modifier = Modifier.padding(start = 8.dp))
        }

        if (!state.encryptedAtRest) {
            Text(
                text = "Warning: secure keystore unavailable on this device. PAT and signature pins fall back to plaintext SharedPreferences.",
                color = Catppuccin.Red,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Button(
            onClick = {
                viewModel.save(
                    sources = normalizedSources,
                    sourcePats = sourcePats,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }

        if (state.savedAt > 0L) {
            Text(
                text = "Saved ${normalizedSources.size} source${if (normalizedSources.size == 1) "" else "s"}.",
                color = Catppuccin.Green,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun SourceEditor(
    index: Int,
    source: SourceDraft,
    canRemove: Boolean,
    onChange: (SourceDraft) -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Catppuccin.Surface0, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "GitHub source ${index + 1}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Catppuccin.Text,
                )
                Text(
                    text = source.user.ifBlank { "Not configured" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                )
            }
            Switch(
                checked = source.enabled,
                onCheckedChange = { onChange(source.copy(enabled = it)) },
                modifier = Modifier.semantics {
                    contentDescription = "Enable GitHub source ${index + 1}"
                },
            )
            if (canRemove) {
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove source", tint = Catppuccin.Red)
                }
            }
        }

        OutlinedTextField(
            value = source.user,
            onValueChange = { onChange(source.copy(user = it)) },
            label = { Text("GitHub user or org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = source.pat,
            onValueChange = { onChange(source.copy(pat = it)) },
            label = { Text("Personal access token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    "Stored encrypted on device. Leave blank for public repos or the shared token fallback.",
                    color = Catppuccin.Subtext,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = Catppuccin.Surface1)

        SettingRow(
            title = "Filter by topic",
            subtitle = "Only show repos tagged with this source's topic.",
            value = source.filterByTopic,
            onChange = { onChange(source.copy(filterByTopic = it)) },
        )

        OutlinedTextField(
            value = source.topic,
            onValueChange = { onChange(source.copy(topic = it)) },
            label = { Text("Topic") },
            singleLine = true,
            enabled = source.filterByTopic,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingRow(
            title = "Show pre-releases",
            subtitle = "Include GitHub releases marked as pre-release for this source.",
            value = source.showPrereleases,
            onChange = { onChange(source.copy(showPrereleases = it)) },
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Catppuccin.Text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Catppuccin.Subtext)
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

private data class SourceDraft(
    val user: String = DEFAULT_GITHUB_USER,
    val topic: String = DEFAULT_GITHUB_TOPIC,
    val filterByTopic: Boolean = false,
    val showPrereleases: Boolean = false,
    val enabled: Boolean = true,
    val pat: String = "",
) {
    fun toSource(): GitHubSource = GitHubSource(
        user = user,
        topic = topic,
        filterByTopic = filterByTopic,
        showPrereleases = showPrereleases,
        enabled = enabled,
    )

    companion object {
        fun from(source: GitHubSource, pat: String): SourceDraft = SourceDraft(
            user = source.user,
            topic = source.topic,
            filterByTopic = source.filterByTopic,
            showPrereleases = source.showPrereleases,
            enabled = source.enabled,
            pat = pat,
        )
    }
}
