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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sysadmin.lasstore.ui.theme.Catppuccin

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var user by remember(state.settings.githubUser) { mutableStateOf(state.settings.githubUser) }
    var topic by remember(state.settings.topic) { mutableStateOf(state.settings.topic) }
    var pat by remember(state.pat) { mutableStateOf(state.pat) }
    var filterByTopic by remember(state.settings.filterByTopic) { mutableStateOf(state.settings.filterByTopic) }
    var showPrereleases by remember(state.settings.showPrereleases) { mutableStateOf(state.settings.showPrereleases) }

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

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("GitHub user or org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = pat,
            onValueChange = { pat = it },
            label = { Text("GitHub personal access token (optional)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = {
                Text(
                    "Boosts API rate limit (60→5000/hr) and unlocks private repos. Stored encrypted on device.",
                    color = Catppuccin.Subtext,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        HorizontalDivider(color = Catppuccin.Surface1)

        SettingRow(
            title = "Filter by topic",
            subtitle = "Only show repos tagged with the topic below.",
            value = filterByTopic,
            onChange = { filterByTopic = it },
        )

        OutlinedTextField(
            value = topic,
            onValueChange = { topic = it },
            label = { Text("Topic") },
            singleLine = true,
            enabled = filterByTopic,
            modifier = Modifier.fillMaxWidth(),
        )

        SettingRow(
            title = "Show pre-releases",
            subtitle = "Include releases marked as pre-release on GitHub.",
            value = showPrereleases,
            onChange = { showPrereleases = it },
        )

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
                    user = user,
                    topic = topic,
                    filterByTopic = filterByTopic,
                    showPrereleases = showPrereleases,
                    pat = pat,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save settings") }

        if (state.savedAt > 0L) {
            Text(
                text = "Saved.",
                color = Catppuccin.Green,
                style = MaterialTheme.typography.bodyMedium,
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
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Catppuccin.Text)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Catppuccin.Subtext)
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}
