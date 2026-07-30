package com.sysadmin.lasstore.ui.settings

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
            },
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
            .padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SettingsHeader()

        SecurityPosture(encryptedAtRest = state.encryptedAtRest)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "SOURCE REGISTRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = Catppuccin.MauveStrong,
                )
                Text(
                    text = "GitHub owners and organizations",
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
            }
            Surface(
                shape = RoundedCornerShape(50),
                color = Catppuccin.Surface1,
                border = BorderStroke(1.dp, Catppuccin.Stroke),
            ) {
                Text(
                    text = normalizedSources.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Catppuccin.MauveStrong,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
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
            border = BorderStroke(1.dp, Catppuccin.StrokeBright),
            contentPadding = PaddingValues(vertical = 13.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Add GitHub source")
        }

        Button(
            onClick = {
                viewModel.save(
                    sources = normalizedSources,
                    sourcePats = sourcePats,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Catppuccin.MauveStrong,
                contentColor = Catppuccin.Crust,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.CloudSync,
                contentDescription = null,
                modifier = Modifier.size(19.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Save source registry")
        }

        if (state.savedAt > 0L) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = Catppuccin.Mint.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Catppuccin.Mint.copy(alpha = 0.28f)),
            ) {
                Text(
                    text = "Registry saved · ${normalizedSources.size} source${if (normalizedSources.size == 1) "" else "s"} ready to sync.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Mint,
                    modifier = Modifier.padding(13.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsHeader() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "CONTROL CENTER",
                style = MaterialTheme.typography.labelSmall,
                color = Catppuccin.MauveStrong,
            )
            Icon(
                imageVector = Icons.Default.Hub,
                contentDescription = null,
                tint = Catppuccin.MauveStrong,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = "Sources & access",
            style = MaterialTheme.typography.headlineMedium,
            color = Catppuccin.TextStrong,
        )
        Text(
            text = "Choose which release shelves this device can see.",
            style = MaterialTheme.typography.bodyMedium,
            color = Catppuccin.Subtext,
        )
    }
}

@Composable
private fun SecurityPosture(encryptedAtRest: Boolean) {
    val accent = if (encryptedAtRest) Catppuccin.Mint else Catppuccin.Red
    val title = if (encryptedAtRest) "Secrets protected on device" else "Secure keystore unavailable"
    val body = if (encryptedAtRest) {
        "Personal access tokens and publisher pins are encrypted with Android Keystore."
    } else {
        "Tokens and publisher pins are using a plaintext fallback on this device."
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = accent.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(accent.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = Catppuccin.TextStrong,
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
            }
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
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Catppuccin.TextStrong,
        unfocusedTextColor = Catppuccin.TextStrong,
        disabledTextColor = Catppuccin.Overlay,
        focusedContainerColor = Catppuccin.Crust.copy(alpha = 0.55f),
        unfocusedContainerColor = Catppuccin.Crust.copy(alpha = 0.55f),
        disabledContainerColor = Catppuccin.Crust.copy(alpha = 0.28f),
        focusedBorderColor = Catppuccin.Mauve,
        unfocusedBorderColor = Catppuccin.StrokeBright,
        disabledBorderColor = Catppuccin.Stroke,
        focusedLabelColor = Catppuccin.MauveStrong,
        unfocusedLabelColor = Catppuccin.Subtext,
        disabledLabelColor = Catppuccin.Overlay,
        cursorColor = Catppuccin.MauveStrong,
        focusedSupportingTextColor = Catppuccin.Subtext,
        unfocusedSupportingTextColor = Catppuccin.Subtext,
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = Catppuccin.PanelRaised,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(15.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(Catppuccin.Mauve.copy(alpha = 0.1f), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = null,
                        tint = Catppuccin.MauveStrong,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Source ${(index + 1).toString().padStart(2, '0')}",
                        style = MaterialTheme.typography.titleMedium,
                        color = Catppuccin.TextStrong,
                    )
                    Text(
                        text = source.user.ifBlank { "Not configured" },
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = source.enabled,
                    onCheckedChange = { onChange(source.copy(enabled = it)) },
                    modifier = Modifier.semantics {
                        contentDescription = "Enable GitHub source ${index + 1}"
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Catppuccin.Crust,
                        checkedTrackColor = Catppuccin.MauveStrong,
                        uncheckedThumbColor = Catppuccin.Subtext,
                        uncheckedTrackColor = Catppuccin.Surface2,
                        uncheckedBorderColor = Catppuccin.StrokeBright,
                    ),
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Remove source",
                            tint = Catppuccin.Red,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = source.user,
                onValueChange = { onChange(source.copy(user = it)) },
                label = { Text("GitHub user or organization") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = source.pat,
                onValueChange = { onChange(source.copy(pat = it)) },
                label = { Text("Personal access token") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                supportingText = {
                    Text("Optional · unlocks private repos and higher API limits.")
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            HorizontalDivider(color = Catppuccin.Stroke)

            SettingRow(
                title = "Filter by topic",
                subtitle = "Only show repos tagged with this source’s topic.",
                value = source.filterByTopic,
                onChange = { onChange(source.copy(filterByTopic = it)) },
            )

            OutlinedTextField(
                value = source.topic,
                onValueChange = { onChange(source.copy(topic = it)) },
                label = { Text("Repository topic") },
                singleLine = true,
                enabled = source.filterByTopic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            SettingRow(
                title = "Show pre-releases",
                subtitle = "Include releases marked as pre-release by GitHub.",
                value = source.showPrereleases,
                onChange = { onChange(source.copy(showPrereleases = it)) },
            )
        }
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Catppuccin.Crust,
                checkedTrackColor = Catppuccin.MauveStrong,
                uncheckedThumbColor = Catppuccin.Subtext,
                uncheckedTrackColor = Catppuccin.Surface2,
                uncheckedBorderColor = Catppuccin.StrokeBright,
            ),
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
