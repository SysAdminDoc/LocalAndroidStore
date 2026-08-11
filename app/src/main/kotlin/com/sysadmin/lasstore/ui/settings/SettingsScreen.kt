package com.sysadmin.lasstore.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import android.os.Build
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import java.text.DateFormat
import java.util.Date
import com.sysadmin.lasstore.data.DEFAULT_GITHUB_TOPIC
import com.sysadmin.lasstore.data.DEFAULT_GITHUB_USER
import com.sysadmin.lasstore.data.AccentColor
import com.sysadmin.lasstore.data.AppThemeMode
import com.sysadmin.lasstore.data.FdroidSource
import com.sysadmin.lasstore.data.GitHubSource
import com.sysadmin.lasstore.data.ServiceLocator
import com.sysadmin.lasstore.data.SourceDirectoryEntry
import com.sysadmin.lasstore.data.normalizeFdroidSources
import com.sysadmin.lasstore.data.normalizeSources
import com.sysadmin.lasstore.data.validateFdroidSources
import com.sysadmin.lasstore.data.validateSources
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.install.ShizukuStatus
import com.sysadmin.lasstore.install.ExternalLaunchResult
import com.sysadmin.lasstore.install.safeLaunchExternalIntent
import com.sysadmin.lasstore.ui.theme.Catppuccin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onOpenNotificationSettings: () -> Unit = {},
    activityResumed: Flow<Unit> = emptyFlow(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val libraryShareTitle = stringResource(R.string.share_library_export)
    val libraryShareFailure = stringResource(R.string.library_export_share_failed)
    val importLibrary = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::importLibrary) }

    LaunchedEffect(state.libraryExportFile) {
        val file = state.libraryExportFile ?: return@LaunchedEffect
        val chooser = Intent.createChooser(
            ServiceLocator.libraryExport.shareIntent(file),
            libraryShareTitle,
        )
        when (
            val result = safeLaunchExternalIntent(
                intent = chooser,
                canResolve = { candidate ->
                    candidate.resolveActivity(context.packageManager) != null
                },
                start = { candidate -> context.startActivity(candidate) },
                failureMessage = libraryShareFailure,
            )
        ) {
            ExternalLaunchResult.Started -> Unit
            is ExternalLaunchResult.Failed -> viewModel.reportLibraryError(result.message)
        }
        viewModel.clearLibraryExportFile()
    }

    LaunchedEffect(activityResumed) {
        viewModel.refreshShizuku()
        activityResumed.collect { viewModel.refreshShizuku() }
    }

    var drafts by remember(state.settings.sources, state.sourcePats) {
        mutableStateOf(
            state.settings.sources.map { source ->
                SourceDraft.from(source, state.sourcePats[source.key].orEmpty())
            },
        )
    }

    val draftSources = drafts.map { it.toSource() }
    val validationError = validateSources(draftSources)
    val normalizedSources = normalizeSources(draftSources)
    val sourcePats = drafts
        .mapNotNull { draft ->
            if (draft.user.isBlank()) null else draft.toSource().key to draft.pat
        }
        .distinctBy { it.first }
        .toMap()
    var fdroidDrafts by remember(state.settings.fdroidSources) {
        mutableStateOf(
            state.settings.fdroidSources.map(FdroidSourceDraft::from),
        )
    }
    var hideUnverifiedSources by remember(state.settings.hideUnverifiedSources) {
        mutableStateOf(state.settings.hideUnverifiedSources)
    }
    var themeMode by remember(state.settings.themeMode) {
        mutableStateOf(state.settings.themeMode)
    }
    var accentColor by remember(state.settings.accentColor) {
        mutableStateOf(state.settings.accentColor)
    }
    var dynamicColor by remember(state.settings.dynamicColor) {
        mutableStateOf(state.settings.dynamicColor)
    }
    var highContrast by remember(state.settings.highContrast) {
        mutableStateOf(state.settings.highContrast)
    }
    var dailyUpdateCap by remember(state.settings.dailyUpdateCap) {
        mutableStateOf(state.settings.dailyUpdateCap)
    }
    var sourceDirectoryUrl by remember(state.settings.sourceDirectoryUrl) {
        mutableStateOf(state.settings.sourceDirectoryUrl)
    }
    var socks5ProxyEnabled by remember(state.settings.socks5ProxyEnabled) {
        mutableStateOf(state.settings.socks5ProxyEnabled)
    }
    var socks5ProxyHost by remember(state.settings.socks5ProxyHost) {
        mutableStateOf(state.settings.socks5ProxyHost)
    }
    var socks5ProxyPort by remember(state.settings.socks5ProxyPort) {
        mutableStateOf(state.settings.socks5ProxyPort.toString())
    }
    val draftFdroidSources = fdroidDrafts.map(FdroidSourceDraft::toSource)
    val fdroidValidationError = validateFdroidSources(draftFdroidSources)
    val normalizedFdroidSources = normalizeFdroidSources(draftFdroidSources)
    val persistedFdroidDrafts = state.settings.fdroidSources.map(FdroidSourceDraft::from)
    val persistedDrafts = state.settings.sources.map { source ->
        SourceDraft.from(source, state.sourcePats[source.key].orEmpty())
    }
    val draftsDirty = validationError != null ||
        drafts != persistedDrafts ||
        normalizedSources != normalizeSources(state.settings.sources) ||
        normalizedSourcePats(sourcePats) != normalizedSourcePats(state.sourcePats) ||
        fdroidDrafts != persistedFdroidDrafts ||
        normalizedFdroidSources != normalizeFdroidSources(state.settings.fdroidSources) ||
        hideUnverifiedSources != state.settings.hideUnverifiedSources ||
        themeMode != state.settings.themeMode ||
        accentColor != state.settings.accentColor ||
        dynamicColor != state.settings.dynamicColor ||
        highContrast != state.settings.highContrast ||
        dailyUpdateCap != state.settings.dailyUpdateCap

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

        LibraryBackupSettings(
            state = state,
            onExport = viewModel::exportLibrary,
            onImport = {
                importLibrary.launch(
                    arrayOf("application/octet-stream", "application/zip", "*/*"),
                )
            },
            onClearRestore = viewModel::clearPendingLibraryRestore,
        )

        SourceDirectorySettings(
            url = sourceDirectoryUrl,
            entries = state.sourceDirectoryEntries,
            addedKeys = state.sourceDirectoryAddedKeys,
            busy = state.sourceDirectoryBusy,
            error = state.sourceDirectoryError,
            message = state.sourceDirectoryMessage,
            onUrlChange = { sourceDirectoryUrl = it },
            onFetch = { viewModel.fetchSourceDirectory(sourceDirectoryUrl) },
            onAdd = viewModel::addSourceDirectoryEntry,
        )

        Socks5ProxySettings(
            enabled = socks5ProxyEnabled,
            host = socks5ProxyHost,
            port = socks5ProxyPort,
            saving = state.proxySaving,
            message = state.proxyMessage,
            error = state.proxyError,
            onEnabledChange = { socks5ProxyEnabled = it },
            onHostChange = { socks5ProxyHost = it },
            onPortChange = { socks5ProxyPort = it },
            onSave = {
                viewModel.saveSocks5Proxy(
                    enabled = socks5ProxyEnabled,
                    host = socks5ProxyHost,
                    portText = socks5ProxyPort,
                )
            },
        )

        AppearanceSettings(
            themeMode = themeMode,
            accentColor = accentColor,
            dynamicColor = dynamicColor,
            highContrast = highContrast,
            onThemeModeChange = { themeMode = it },
            onAccentColorChange = { accentColor = it },
            onDynamicColorChange = { dynamicColor = it },
            onHighContrastChange = { highContrast = it },
        )

        BackgroundUpdatePolicySettings(
            dailyUpdateCap = dailyUpdateCap,
            onDailyUpdateCapChange = { dailyUpdateCap = it },
        )

        ShizukuInstallSettings(
            enabled = state.shizukuSilentInstallEnabled,
            status = state.shizukuStatus,
            onEnabledChange = viewModel::setShizukuSilentInstallEnabled,
            onRequestPermission = viewModel::requestShizukuPermission,
            onOpenManager = viewModel::openShizukuManager,
        )

        SourceVerificationPosture(
            hideUnverifiedSources = hideUnverifiedSources,
            onHideUnverifiedSourcesChange = { hideUnverifiedSources = it },
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            NotificationPosture(onOpenSettings = onOpenNotificationSettings)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.source_registry),
                    style = MaterialTheme.typography.labelSmall,
                    color = Catppuccin.MauveStrong,
                )
                Text(
                    text = stringResource(R.string.github_owners_organizations),
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
                    connection = state.connectionChecks[source.toSource().key],
                    onTestConnection = { viewModel.testConnection(source.user, source.pat) },
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
            Text(stringResource(R.string.add_github_source))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fdroid_repositories),
                    style = MaterialTheme.typography.labelSmall,
                    color = Catppuccin.MauveStrong,
                )
                Text(
                    text = stringResource(R.string.fdroid_repositories_subtitle),
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
                    text = normalizedFdroidSources.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Catppuccin.MauveStrong,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp),
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
            fdroidDrafts.forEachIndexed { index, source ->
                FdroidSourceEditor(
                    index = index,
                    source = source,
                    canRemove = fdroidDrafts.size > 1,
                    onChange = { updated ->
                        fdroidDrafts = fdroidDrafts.toMutableList().also { it[index] = updated }
                    },
                    onRemove = {
                        fdroidDrafts = fdroidDrafts.toMutableList()
                            .also { it.removeAt(index) }
                    },
                )
            }
        }

        OutlinedButton(
            onClick = { fdroidDrafts = fdroidDrafts + FdroidSourceDraft() },
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
            Text(stringResource(R.string.add_fdroid_repository))
        }

        validationError?.let { error ->
            SettingsError(text = error)
        }
        fdroidValidationError?.let { error ->
            SettingsError(text = error)
        }
        state.saveError?.let { error ->
            SettingsError(text = error)
        }

        if (state.registryRecoveryRequired) {
            SettingsError(
                text = stringResource(
                    if (state.registryRecoveryBackupAvailable) {
                        R.string.source_registry_recovery_required
                    } else {
                        R.string.source_registry_recovery_backup_failed
                    },
                ),
            )
            OutlinedButton(
                onClick = {
                    viewModel.replaceMalformedRegistry(
                        sources = draftSources,
                        sourcePats = sourcePats,
                        fdroidSources = draftFdroidSources,
                        hideUnverifiedSources = hideUnverifiedSources,
                        themeMode = themeMode,
                        accentColor = accentColor,
                        dynamicColor = dynamicColor,
                        highContrast = highContrast,
                        dailyUpdateCap = dailyUpdateCap,
                    )
                },
                enabled = validationError == null && fdroidValidationError == null && !state.saving &&
                    state.registryRecoveryBackupAvailable,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Catppuccin.Red.copy(alpha = 0.45f)),
                contentPadding = PaddingValues(vertical = 13.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.Red),
            ) {
                Text(stringResource(R.string.replace_saved_registry))
            }
        }

        Button(
            onClick = {
                viewModel.save(
                    sources = draftSources,
                    sourcePats = sourcePats,
                    fdroidSources = draftFdroidSources,
                    hideUnverifiedSources = hideUnverifiedSources,
                    themeMode = themeMode,
                    accentColor = accentColor,
                    dynamicColor = dynamicColor,
                    highContrast = highContrast,
                    dailyUpdateCap = dailyUpdateCap,
                )
            },
            enabled = validationError == null && fdroidValidationError == null &&
                !state.saving && !state.registryRecoveryRequired,
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
            Text(
                stringResource(
                    if (state.saving) {
                        R.string.saving_source_registry
                    } else {
                        R.string.save_source_registry
                    },
                ),
            )
        }

        when {
            draftsDirty -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Catppuccin.Peach.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Catppuccin.Peach.copy(alpha = 0.28f)),
                ) {
                    Text(
                        text = stringResource(R.string.unsaved_source_changes),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Peach,
                        modifier = Modifier.padding(13.dp),
                    )
                }
            }
            state.saveStatus == SettingsSaveStatus.Saved -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Catppuccin.Mint.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Catppuccin.Mint.copy(alpha = 0.28f)),
                ) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.registry_saved,
                            normalizedSources.size + normalizedFdroidSources.size,
                            normalizedSources.size + normalizedFdroidSources.size,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Mint,
                        modifier = Modifier.padding(13.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsError(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.Red.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Catppuccin.Red.copy(alpha = 0.3f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Red,
            modifier = Modifier.padding(13.dp),
        )
    }
}

@Composable
private fun LibraryBackupSettings(
    state: SettingsUiState,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onClearRestore: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.library_backup_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.library_backup_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onExport,
                    enabled = !state.libraryExportBusy && !state.libraryImportBusy,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Catppuccin.MauveStrong,
                        contentColor = Catppuccin.Crust,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (state.libraryExportBusy) {
                                R.string.exporting_library
                            } else {
                                R.string.export_library
                            },
                        ),
                    )
                }
                OutlinedButton(
                    onClick = onImport,
                    enabled = !state.libraryExportBusy && !state.libraryImportBusy,
                    modifier = Modifier.weight(1f),
                    border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Catppuccin.MauveStrong,
                    ),
                ) {
                    Text(
                        stringResource(
                            if (state.libraryImportBusy) {
                                R.string.importing_library
                            } else {
                                R.string.import_library
                            },
                        ),
                    )
                }
            }
            state.pendingLibraryRestoreCount.takeIf { it > 0 }?.let { count ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    color = Catppuccin.Peach.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, Catppuccin.Peach.copy(alpha = 0.28f)),
                ) {
                    Column(
                        modifier = Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.library_restore_pending, count),
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Peach,
                        )
                        OutlinedButton(
                            onClick = onClearRestore,
                            border = BorderStroke(1.dp, Catppuccin.Peach.copy(alpha = 0.45f)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Catppuccin.Peach,
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Text(stringResource(R.string.clear_library_restore))
                        }
                    }
                }
            }
            state.libraryMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Mint,
                )
            }
            state.libraryError?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Red,
                )
            }
        }
    }
}

@Composable
private fun SourceDirectorySettings(
    url: String,
    entries: List<SourceDirectoryEntry>,
    addedKeys: Set<String>,
    busy: Boolean,
    error: String?,
    message: String?,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onAdd: (SourceDirectoryEntry) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.source_directory_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.source_directory_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                label = { Text(stringResource(R.string.source_directory_url)) },
                supportingText = { Text(stringResource(R.string.source_directory_url_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            Button(
                onClick = onFetch,
                enabled = !busy && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Catppuccin.MauveStrong,
                    contentColor = Catppuccin.Crust,
                ),
            ) {
                Text(stringResource(if (busy) R.string.loading_source_directory else R.string.load_source_directory))
            }
            message?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Mint,
                )
            }
            error?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Red,
                )
            }
            entries.forEach { entry ->
                val added = entry.sourceKey in addedKeys
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Catppuccin.Surface0,
                    border = BorderStroke(1.dp, Catppuccin.Stroke),
                ) {
                    Column(
                        modifier = Modifier.padding(11.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Catppuccin.TextStrong,
                                )
                                Text(
                                    text = entry.github?.displayName
                                        ?: entry.fdroid?.displayName
                                        ?: entry.sourceKey,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Catppuccin.Subtext,
                                )
                            }
                            OutlinedButton(
                                onClick = { onAdd(entry) },
                                enabled = !added,
                                border = BorderStroke(
                                    1.dp,
                                    if (added) Catppuccin.Mint.copy(alpha = 0.35f)
                                    else Catppuccin.StrokeBright,
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (added) Catppuccin.Mint else Catppuccin.MauveStrong,
                                ),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(
                                    stringResource(
                                        if (added) R.string.source_directory_added
                                        else R.string.add_curated_source,
                                    ),
                                )
                            }
                        }
                        entry.description?.takeIf { it.isNotBlank() }?.let { description ->
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Subtext,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Socks5ProxySettings(
    enabled: Boolean,
    host: String,
    port: String,
    saving: Boolean,
    message: String?,
    error: String?,
    onEnabledChange: (Boolean) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.socks5_proxy_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.socks5_proxy_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            SettingRow(
                title = stringResource(R.string.socks5_proxy_toggle),
                subtitle = stringResource(R.string.socks5_proxy_toggle_subtitle),
                value = enabled,
                onChange = onEnabledChange,
            )
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.socks5_proxy_host)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.socks5_proxy_port)) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
            )
            OutlinedButton(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                contentPadding = PaddingValues(vertical = 11.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
            ) {
                Text(stringResource(if (saving) R.string.saving_proxy else R.string.save_proxy))
            }
            message?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = Catppuccin.Mint)
            }
            error?.let { text ->
                Text(text, style = MaterialTheme.typography.bodySmall, color = Catppuccin.Red)
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
                text = stringResource(R.string.control_center),
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
            text = stringResource(R.string.sources_access),
            style = MaterialTheme.typography.headlineMedium,
            color = Catppuccin.TextStrong,
        )
        Text(
            text = stringResource(R.string.choose_release_shelves),
            style = MaterialTheme.typography.bodyMedium,
            color = Catppuccin.Subtext,
        )
    }
}

@Composable
private fun AppearanceSettings(
    themeMode: AppThemeMode,
    accentColor: AccentColor,
    dynamicColor: Boolean,
    highContrast: Boolean,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onAccentColorChange: (AccentColor) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onHighContrastChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.appearance_settings_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.appearance_settings_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            Text(
                text = stringResource(R.string.theme_mode),
                style = MaterialTheme.typography.labelMedium,
                color = Catppuccin.TextStrong,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeChoice(
                    label = stringResource(R.string.theme_dark),
                    selected = themeMode == AppThemeMode.Dark,
                    onClick = { onThemeModeChange(AppThemeMode.Dark) },
                    modifier = Modifier.weight(1f),
                )
                ThemeChoice(
                    label = stringResource(R.string.theme_light),
                    selected = themeMode == AppThemeMode.Light,
                    onClick = { onThemeModeChange(AppThemeMode.Light) },
                    modifier = Modifier.weight(1f),
                )
            }
            SettingRow(
                title = stringResource(R.string.dynamic_color_setting),
                subtitle = stringResource(R.string.dynamic_color_subtitle),
                value = dynamicColor,
                onChange = onDynamicColorChange,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            )
            SettingRow(
                title = stringResource(R.string.high_contrast_setting),
                subtitle = stringResource(R.string.high_contrast_subtitle),
                value = highContrast,
                onChange = onHighContrastChange,
            )
            Text(
                text = stringResource(R.string.accent_color),
                style = MaterialTheme.typography.labelMedium,
                color = Catppuccin.TextStrong,
            )
            AccentPicker(
                selected = accentColor,
                onSelected = { selected ->
                    if (selected != null) onAccentColorChange(selected)
                },
            )
        }
    }
}

@Composable
private fun BackgroundUpdatePolicySettings(
    dailyUpdateCap: Int,
    onDailyUpdateCapChange: (Int) -> Unit,
) {
    val capChoices = listOf(0, 1, 3, 5, 10)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.background_update_policy_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.background_update_policy_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            Text(
                text = stringResource(R.string.daily_update_cap),
                style = MaterialTheme.typography.labelMedium,
                color = Catppuccin.TextStrong,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                capChoices.forEach { cap ->
                    val label = if (cap == 0) {
                        stringResource(R.string.daily_update_cap_off)
                    } else {
                        stringResource(R.string.daily_update_cap_value, cap)
                    }
                    ThemeChoice(
                        label = label,
                        selected = dailyUpdateCap == cap,
                        onClick = { onDailyUpdateCapChange(cap) },
                    )
                }
            }
            Text(
                text = stringResource(R.string.background_update_cadence_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
        }
    }
}

@Composable
private fun ThemeChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(14.dp),
        color = if (selected) Catppuccin.Mauve.copy(alpha = 0.14f) else Catppuccin.Surface0,
        border = BorderStroke(
            1.dp,
            if (selected) Catppuccin.Mauve else Catppuccin.StrokeBright,
        ),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Catppuccin.MauveStrong else Catppuccin.Subtext,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
        )
    }
}

@Composable
private fun AccentPicker(
    selected: AccentColor?,
    onSelected: (AccentColor?) -> Unit,
    allowGlobal: Boolean = false,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (allowGlobal) {
            AccentChoice(
                label = stringResource(R.string.use_global_accent),
                tint = Catppuccin.Mauve,
                selected = selected == null,
                onClick = { onSelected(null) },
            )
        }
        AccentColor.entries.forEach { accent ->
            AccentChoice(
                label = stringResource(accentLabelRes(accent)),
                tint = Catppuccin.accent(accent),
                selected = selected == accent,
                onClick = { onSelected(accent) },
            )
        }
    }
}

@Composable
private fun AccentChoice(
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(58.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(tint, CircleShape)
                .then(
                    if (selected) {
                        Modifier.border(3.dp, Catppuccin.TextStrong, CircleShape)
                    } else {
                        Modifier.border(1.dp, tint.copy(alpha = 0.65f), CircleShape)
                    },
                ),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Catppuccin.TextStrong else Catppuccin.Subtext,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun accentLabelRes(accent: AccentColor): Int = when (accent) {
    AccentColor.Mauve -> R.string.accent_mauve
    AccentColor.Sapphire -> R.string.accent_sapphire
    AccentColor.Green -> R.string.accent_green
    AccentColor.Yellow -> R.string.accent_yellow
    AccentColor.Red -> R.string.accent_red
    AccentColor.Pink -> R.string.accent_pink
    AccentColor.Teal -> R.string.accent_teal
    AccentColor.Lavender -> R.string.accent_lavender
}

@Composable
private fun SecurityPosture(encryptedAtRest: Boolean) {
    val accent = if (encryptedAtRest) Catppuccin.Mint else Catppuccin.Red
    val title = stringResource(
        if (encryptedAtRest) {
            R.string.secrets_protected
        } else {
            R.string.secure_keystore_unavailable
        },
    )
    val body = if (encryptedAtRest) {
        stringResource(R.string.tokens_encrypted)
    } else {
        stringResource(R.string.tokens_plaintext_fallback)
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
private fun ShizukuInstallSettings(
    enabled: Boolean,
    status: ShizukuStatus,
    onEnabledChange: (Boolean) -> Unit,
    onRequestPermission: () -> Unit,
    onOpenManager: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.shizuku_install_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.shizuku_install_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            Text(
                text = when (status) {
                    ShizukuStatus.Unavailable -> stringResource(R.string.shizuku_status_unavailable)
                    ShizukuStatus.PermissionRequired -> stringResource(R.string.shizuku_status_permission_required)
                    ShizukuStatus.Ready -> stringResource(R.string.shizuku_status_ready)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status == ShizukuStatus.Ready) {
                    Catppuccin.Green
                } else {
                    Catppuccin.Subtext
                },
            )
            SettingRow(
                title = stringResource(R.string.shizuku_no_prompt_toggle),
                subtitle = stringResource(R.string.shizuku_no_prompt_toggle_subtitle),
                value = enabled,
                onChange = onEnabledChange,
            )
            when (status) {
                ShizukuStatus.Unavailable -> OutlinedButton(
                    onClick = onOpenManager,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
                ) {
                    Text(stringResource(R.string.open_shizuku))
                }
                ShizukuStatus.PermissionRequired -> OutlinedButton(
                    onClick = onRequestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                    contentPadding = PaddingValues(vertical = 11.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
                ) {
                    Text(stringResource(R.string.grant_shizuku_access))
                }
                ShizukuStatus.Ready -> Unit
            }
        }
    }
}

@Composable
private fun SourceVerificationPosture(
    hideUnverifiedSources: Boolean,
    onHideUnverifiedSourcesChange: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.source_verification_settings_title),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.source_verification_settings_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            SettingRow(
                title = stringResource(R.string.hide_unverified_sources_setting),
                subtitle = stringResource(R.string.hide_unverified_sources_subtitle),
                value = hideUnverifiedSources,
                onChange = onHideUnverifiedSourcesChange,
            )
        }
    }
}

@Composable
private fun NotificationPosture(onOpenSettings: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Catppuccin.Surface1,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            Text(
                text = stringResource(R.string.background_update_notifications),
                style = MaterialTheme.typography.titleSmall,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = stringResource(R.string.background_update_notifications_body),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Subtext,
            )
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                contentPadding = PaddingValues(vertical = 11.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
            ) {
                Text(stringResource(R.string.open_android_notification_settings))
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
    connection: ConnectionCheckState?,
    onTestConnection: () -> Unit,
) {
    val enableSourceDescription = stringResource(R.string.enable_github_source, index + 1)
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
                        text = stringResource(R.string.source_number, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Catppuccin.TextStrong,
                    )
                    Text(
                        text = source.user.ifBlank { stringResource(R.string.not_configured) },
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
                        contentDescription = enableSourceDescription
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
                            contentDescription = stringResource(R.string.remove_source),
                            tint = Catppuccin.Red,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = source.user,
                onValueChange = { onChange(source.copy(user = it)) },
                label = { Text(stringResource(R.string.github_user_or_organization)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = source.pat,
                onValueChange = { onChange(source.copy(pat = it)) },
                label = { Text(stringResource(R.string.personal_access_token)) },
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
                    Text(
                        stringResource(
                            if (source.pat.trim().startsWith("github_pat_")) {
                                R.string.fine_grained_pat_supporting_text
                            } else {
                                R.string.pat_supporting_text
                            },
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = source.threatModel,
                onValueChange = { onChange(source.copy(threatModel = it)) },
                label = { Text(stringResource(R.string.source_threat_model)) },
                supportingText = { Text(stringResource(R.string.source_threat_model_supporting_text)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            OutlinedButton(
                onClick = onTestConnection,
                enabled = source.user.trim().isNotBlank() && connection?.running != true,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Catppuccin.StrokeBright),
                contentPadding = PaddingValues(vertical = 11.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
            ) {
                Icon(
                    imageVector = Icons.Default.CloudSync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (connection?.running == true) {
                            R.string.testing_connection
                        } else {
                            R.string.test_connection
                        },
                    ),
                )
            }

            connection?.let { ConnectionFeedback(it) }

            Text(
                text = stringResource(R.string.source_accent),
                style = MaterialTheme.typography.labelMedium,
                color = Catppuccin.TextStrong,
            )
            AccentPicker(
                selected = source.accent,
                allowGlobal = true,
                onSelected = { onChange(source.copy(accent = it)) },
            )

            OutlinedTextField(
                value = source.brandingUrl,
                onValueChange = { onChange(source.copy(brandingUrl = it)) },
                label = { Text(stringResource(R.string.source_branding_feed_url)) },
                supportingText = {
                    Text(stringResource(R.string.source_branding_feed_supporting_text))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            OutlinedTextField(
                value = source.threatModel,
                onValueChange = { onChange(source.copy(threatModel = it)) },
                label = { Text(stringResource(R.string.source_threat_model)) },
                supportingText = { Text(stringResource(R.string.source_threat_model_supporting_text)) },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            HorizontalDivider(color = Catppuccin.Stroke)

            SettingRow(
                title = stringResource(R.string.filter_by_topic_setting),
                subtitle = stringResource(R.string.filter_by_topic_subtitle),
                value = source.filterByTopic,
                onChange = { onChange(source.copy(filterByTopic = it)) },
            )

            OutlinedTextField(
                value = source.topic,
                onValueChange = { onChange(source.copy(topic = it)) },
                label = { Text(stringResource(R.string.repository_topic)) },
                singleLine = true,
                enabled = source.filterByTopic,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            SettingRow(
                title = stringResource(R.string.show_prereleases_setting),
                subtitle = stringResource(R.string.show_prereleases_subtitle),
                value = source.showPrereleases,
                onChange = { onChange(source.copy(showPrereleases = it)) },
            )
        }
    }
}

@Composable
private fun FdroidSourceEditor(
    index: Int,
    source: FdroidSourceDraft,
    canRemove: Boolean,
    onChange: (FdroidSourceDraft) -> Unit,
    onRemove: () -> Unit,
) {
    val enableRepositoryDescription = stringResource(
        R.string.enable_fdroid_repository,
        index + 1,
    )
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Catppuccin.TextStrong,
        unfocusedTextColor = Catppuccin.TextStrong,
        disabledTextColor = Catppuccin.Overlay,
        focusedContainerColor = Catppuccin.Crust.copy(alpha = 0.55f),
        unfocusedContainerColor = Catppuccin.Crust.copy(alpha = 0.55f),
        focusedBorderColor = Catppuccin.Mauve,
        unfocusedBorderColor = Catppuccin.StrokeBright,
        focusedLabelColor = Catppuccin.MauveStrong,
        unfocusedLabelColor = Catppuccin.Subtext,
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
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Catppuccin.MauveStrong,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.fdroid_repository_number, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                        color = Catppuccin.TextStrong,
                    )
                    Text(
                        text = source.endpointUrl.ifBlank { stringResource(R.string.not_configured) },
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
                        contentDescription = enableRepositoryDescription
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
                            contentDescription = stringResource(R.string.remove_fdroid_repository),
                            tint = Catppuccin.Red,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = source.endpointUrl,
                onValueChange = { onChange(source.copy(endpointUrl = it)) },
                label = { Text(stringResource(R.string.fdroid_index_url)) },
                supportingText = { Text(stringResource(R.string.fdroid_index_url_supporting_text)) },
                singleLine = false,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )

            Text(
                text = stringResource(R.string.source_accent),
                style = MaterialTheme.typography.labelMedium,
                color = Catppuccin.TextStrong,
            )
            AccentPicker(
                selected = source.accent,
                allowGlobal = true,
                onSelected = { onChange(source.copy(accent = it)) },
            )

            OutlinedTextField(
                value = source.brandingUrl,
                onValueChange = { onChange(source.copy(brandingUrl = it)) },
                label = { Text(stringResource(R.string.source_branding_feed_url)) },
                supportingText = {
                    Text(stringResource(R.string.source_branding_feed_supporting_text))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors,
            )
        }
    }
}

@Composable
private fun ConnectionFeedback(state: ConnectionCheckState) {
    state.error?.let { error ->
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Red,
        )
    }
    state.result?.let { result ->
        val access = when {
            result.authenticatedLogin == null ->
                stringResource(R.string.owner_reachable_no_token)
            result.authenticatedOwnerAccess ->
                stringResource(
                    R.string.authenticated_owner_access,
                    result.authenticatedLogin,
                    result.requestedOwner,
                )
            else ->
                stringResource(
                    R.string.authenticated_no_repositories,
                    result.authenticatedLogin,
                    result.requestedOwner,
                )
        }
        Text(
            text = access,
            style = MaterialTheme.typography.bodySmall,
            color = if (result.authenticatedOwnerAccess || result.authenticatedLogin == null) {
                Catppuccin.Mint
            } else {
                Catppuccin.Peach
            },
        )
        val scopes = result.tokenScopes.takeIf { it.isNotEmpty() }?.joinToString()
            .orEmpty()
            .ifBlank { stringResource(R.string.scopes_not_exposed) }
        Text(
            text = stringResource(R.string.scopes, scopes),
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Subtext,
        )
    }
    val remaining = state.result?.rateLimitRemaining ?: state.rateLimitRemaining
    val reset = state.result?.rateLimitResetEpochMillis ?: state.rateLimitResetEpochMillis
    if (remaining != null || reset != null) {
        val resetText = reset?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
        } ?: stringResource(R.string.unknown)
        val remainingText = remaining?.toString() ?: stringResource(R.string.unknown)
        Text(
            text = stringResource(R.string.rate_budget, remainingText, resetText),
            style = MaterialTheme.typography.bodySmall,
            color = Catppuccin.Subtext,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    value: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
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
                color = if (enabled) Catppuccin.TextStrong else Catppuccin.Overlay,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) Catppuccin.Subtext else Catppuccin.Overlay,
            )
        }
        Switch(
            checked = value,
            onCheckedChange = onChange,
            enabled = enabled,
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
    val accent: AccentColor? = null,
    val brandingUrl: String = "",
    val threatModel: String = "",
    val pat: String = "",
) {
    fun toSource(): GitHubSource = GitHubSource(
        user = user,
        topic = topic,
        filterByTopic = filterByTopic,
        showPrereleases = showPrereleases,
        enabled = enabled,
        accent = accent,
        brandingUrl = brandingUrl,
        threatModel = threatModel,
    )

    companion object {
        fun from(source: GitHubSource, pat: String): SourceDraft = SourceDraft(
            user = source.user,
            topic = source.topic,
            filterByTopic = source.filterByTopic,
            showPrereleases = source.showPrereleases,
            enabled = source.enabled,
            accent = source.accent,
            brandingUrl = source.brandingUrl,
            threatModel = source.threatModel,
            pat = pat,
        )
    }
}

private data class FdroidSourceDraft(
    val endpointUrl: String = "",
    val enabled: Boolean = true,
    val accent: AccentColor? = null,
    val brandingUrl: String = "",
    val threatModel: String = "",
) {
    fun toSource(): FdroidSource = FdroidSource(
        endpointUrl = endpointUrl,
        enabled = enabled,
        accent = accent,
        brandingUrl = brandingUrl,
        threatModel = threatModel,
    )

    companion object {
        fun from(source: FdroidSource): FdroidSourceDraft = FdroidSourceDraft(
            endpointUrl = source.endpointUrl,
            enabled = source.enabled,
            accent = source.accent,
            brandingUrl = source.brandingUrl,
            threatModel = source.threatModel,
        )
    }
}

private fun normalizedSourcePats(sourcePats: Map<String, String>): Map<String, String> =
    sourcePats
        .mapNotNull { (key, value) ->
            value.trim().takeIf { it.isNotBlank() }?.let { key to it }
        }
        .toMap()
