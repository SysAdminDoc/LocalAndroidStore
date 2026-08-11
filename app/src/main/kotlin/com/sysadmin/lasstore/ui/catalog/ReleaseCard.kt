package com.sysadmin.lasstore.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.data.DeveloperVerificationCopy
import com.sysadmin.lasstore.data.GhAsset
import com.sysadmin.lasstore.data.normalizeSha256Digest
import com.sysadmin.lasstore.data.UpdateCadence
import com.sysadmin.lasstore.data.UpdateCadenceMode
import com.sysadmin.lasstore.domain.AntiFeatureBadge
import com.sysadmin.lasstore.domain.AntiFeatureSeverity
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.domain.ApkAssetClassifier
import com.sysadmin.lasstore.domain.ReleaseChannel
import com.sysadmin.lasstore.domain.SourceVerificationStatus
import com.sysadmin.lasstore.domain.antiFeatureBadges
import com.sysadmin.lasstore.ui.theme.Catppuccin
import java.time.Instant
import java.text.DateFormat
import java.util.Date

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
    onSetUpdateCadence: (UpdateCadence) -> Unit = {},
    onSaveApk: () -> Unit,
    onReplacePublisherPin: (typedApplicationId: String, independentlyVerified: Boolean) -> Unit,
    onSelectAsset: (GhAsset) -> Unit,
    onAdopt: () -> Unit = {},
    onManualInstall: () -> Unit = {},
    onBrowseHistory: () -> Unit = {},
    onLoadMoreHistory: () -> Unit = {},
    onSelectHistoricalRelease: (HistoricalRelease) -> Unit = {},
    onSelectPreferredSource: (String) -> Unit = {},
    onSetChannelPreference: (ReleaseChannel?) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
    onOpenAdvancedSideloading: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val sourceAccent = Catppuccin.accent(state.sourceAccent)
    val isReleaseStale = remember(state.info.publishedAt) {
        val publishedAt = state.info.publishedAt ?: return@remember false
        val published = runCatching { Instant.parse(publishedAt).toEpochMilli() }.getOrNull()
            ?: return@remember false
        published < System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
    }
    var notesExpanded by rememberSaveable(state.info.handle) { mutableStateOf(false) }
    var assetSelectionVisible by rememberSaveable(
        state.info.handle,
        state.info.assetChoices.size,
    ) { mutableStateOf(false) }
    var trustRecoveryVisible by rememberSaveable(
        state.info.handle,
        state.publisherTrustDetails?.downloadedMetadata?.signingSha256,
    ) {
        mutableStateOf(false)
    }
    var adoptionVisible by rememberSaveable(
        state.info.handle,
        state.unmanagedInstall?.installedSignerSha256,
    ) { mutableStateOf(false) }
    var historyVisible by rememberSaveable(state.info.handle) { mutableStateOf(false) }
    var sourceSelectionVisible by rememberSaveable(
        state.info.applicationId,
        state.alternativeSources.size,
    ) { mutableStateOf(false) }
    var channelSelectionVisible by rememberSaveable(
        state.info.handle,
        state.channelPreference,
    ) { mutableStateOf(false) }
    var sourceVerificationVisible by rememberSaveable(state.info.handle) {
        mutableStateOf(false)
    }
    val antiFeatureBadges = remember(state.info.antiFeatures) {
        antiFeatureBadges(state.info.antiFeatures)
    }
    val cardShape = RoundedCornerShape(24.dp)

    if (assetSelectionVisible) {
        AlertDialog(
            onDismissRequest = { assetSelectionVisible = false },
            title = { Text(stringResource(R.string.choose_apk_variant)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        text = stringResource(R.string.choose_apk_variant_body),
                        color = Catppuccin.Subtext,
                    )
                    state.info.assetChoices.forEach { candidate ->
                        TextButton(
                            onClick = {
                                assetSelectionVisible = false
                                onSelectAsset(candidate)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = candidate.name,
                                    color = Catppuccin.TextStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.apk_variant_details,
                                        ApkAssetClassifier.variantLabel(candidate.name),
                                        formatAssetSize(candidate.size),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Catppuccin.Subtext,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { assetSelectionVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (sourceVerificationVisible) {
        SourceVerificationDialog(
            sourceLabel = state.info.sourceLabel,
            status = state.sourceVerification,
            onDismiss = { sourceVerificationVisible = false },
            onOpenAdvancedSideloading = {
                sourceVerificationVisible = false
                onOpenAdvancedSideloading()
            },
        )
    }

    if (trustRecoveryVisible) {
        state.publisherTrustDetails?.let { details ->
            PublisherTrustRecoveryDialog(
                details = details,
                busy = state.publisherTrustRecoveryBusy,
                onDismiss = { trustRecoveryVisible = false },
                onConfirm = { typedApplicationId, independentlyVerified ->
                    trustRecoveryVisible = false
                    onReplacePublisherPin(typedApplicationId, independentlyVerified)
                },
            )
        }
    }

    if (adoptionVisible) {
        state.unmanagedInstall?.let { unmanaged ->
            val signer = unmanaged.installedSignerSha256
                ?: stringResource(R.string.unknown)
            AlertDialog(
                onDismissRequest = { adoptionVisible = false },
                title = { Text(stringResource(R.string.adopt_installed_app_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(stringResource(R.string.adopt_installed_app_body))
                        Text(stringResource(R.string.installed_elsewhere_package, unmanaged.applicationId))
                        Text(
                            stringResource(
                                R.string.installed_elsewhere_version,
                                unmanaged.installedVersionName ?: stringResource(R.string.unknown),
                                unmanaged.installedVersionCode,
                            ),
                        )
                        Text(stringResource(R.string.installed_elsewhere_signer, signer))
                        Text(stringResource(R.string.installed_elsewhere_source, unmanaged.source))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            adoptionVisible = false
                            onAdopt()
                        },
                    ) { Text(stringResource(R.string.adopt_installed_app)) }
                },
                dismissButton = {
                    TextButton(onClick = { adoptionVisible = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }

    if (historyVisible) {
        val history = state.releaseHistory
        AlertDialog(
            onDismissRequest = { historyVisible = false },
            title = { Text(stringResource(R.string.release_history_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.release_history_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                    )
                    if (history == null || (history.loading && history.releases.isEmpty())) {
                        Text(stringResource(R.string.release_history_loading))
                    }
                    history?.error?.let { error ->
                        Text(
                            text = stringResource(R.string.release_history_error, error),
                            color = Catppuccin.Red,
                        )
                        if (history.loading.not()) {
                            OutlinedButton(
                                onClick = onBrowseHistory,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.release_history_retry))
                            }
                        }
                    }
                    if (history != null && history.releases.isEmpty() && !history.loading) {
                        Text(stringResource(R.string.release_history_empty), color = Catppuccin.Subtext)
                    }
                    history?.releases?.forEach { historical ->
                        val info = historical.info
                        val prereleaseLabel = if (historical.release.prerelease) {
                            stringResource(R.string.release_history_prerelease)
                        } else {
                            null
                        }
                        OutlinedButton(
                            onClick = {
                                if (info != null) {
                                    historyVisible = false
                                    onSelectHistoricalRelease(historical)
                                }
                            },
                            enabled = info != null,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = historical.release.name
                                        ?.takeIf { it.isNotBlank() }
                                        ?: historical.release.tagName,
                                    color = Catppuccin.TextStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        historical.release.tagName,
                                        formatHistoricalDate(historical.release.publishedAt),
                                        prereleaseLabel,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Catppuccin.Subtext,
                                )
                                if (info == null) {
                                    Text(
                                        text = stringResource(R.string.release_history_no_apk),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Catppuccin.Peach,
                                    )
                                } else {
                                    val digest = normalizeSha256Digest(info.asset.digest)
                                        ?: stringResource(R.string.unknown)
                                    Text(
                                        text = stringResource(
                                            R.string.release_history_asset,
                                            info.asset.name,
                                            formatAssetSize(info.asset.size),
                                            digest.take(16),
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Catppuccin.Subtext,
                                    )
                                    val inspectedVersion = historical.inspectedVersionCode
                                    if (inspectedVersion != null) {
                                        Text(
                                            text = stringResource(
                                                R.string.release_history_inspected,
                                                historical.inspectedVersionName
                                                    ?: stringResource(R.string.unknown),
                                                inspectedVersion,
                                                historical.inspectedSignerSha256
                                                    ?: stringResource(R.string.unknown),
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Catppuccin.Mint,
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.release_history_inspect_on_select),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Catppuccin.Sapphire,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (history?.loading == true && history.releases.isNotEmpty()) {
                        Text(stringResource(R.string.release_history_loading), color = Catppuccin.Subtext)
                    }
                    if (history?.nextPage != null && history.error == null) {
                        OutlinedButton(
                            onClick = onLoadMoreHistory,
                            enabled = history.loading.not(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.release_history_load_more))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { historyVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (sourceSelectionVisible && state.alternativeSources.isNotEmpty()) {
        val sourceOptions = listOf(state.info) + state.alternativeSources
        AlertDialog(
            onDismissRequest = { sourceSelectionVisible = false },
            title = { Text(stringResource(R.string.preferred_source_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.preferred_source_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                    )
                    sourceOptions.forEach { candidate ->
                        TextButton(
                            onClick = {
                                sourceSelectionVisible = false
                                onSelectPreferredSource(candidate.sourceKey)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = sourceDisplayName(candidate),
                                    color = Catppuccin.TextStrong,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        candidate.versionName ?: candidate.tagName,
                                        candidate.asset.name,
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Catppuccin.Subtext,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sourceSelectionVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (channelSelectionVisible) {
        AlertDialog(
            onDismissRequest = { channelSelectionVisible = false },
            title = { Text(stringResource(R.string.release_channel_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.release_channel_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Subtext,
                    )
                    TextButton(
                        onClick = {
                            channelSelectionVisible = false
                            onSetChannelPreference(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                    ) {
                        channelOptionText(
                            label = stringResource(R.string.release_channel_any),
                            selected = state.channelPreference == null,
                        )
                    }
                    listOf(
                        ReleaseChannel.STABLE,
                        ReleaseChannel.BETA,
                        ReleaseChannel.ALPHA,
                        ReleaseChannel.NIGHTLY,
                        ReleaseChannel.RC,
                        ReleaseChannel.DEV,
                    ).forEach { channel ->
                        TextButton(
                            onClick = {
                                channelSelectionVisible = false
                                onSetChannelPreference(channel)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        ) {
                            channelOptionText(
                                label = releaseChannelName(channel),
                                selected = state.channelPreference == channel,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { channelSelectionVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
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
                        sourceAccent.copy(alpha = 0.16f),
                        Catppuccin.Panel,
                        Catppuccin.PanelRaised.copy(alpha = 0.86f),
                    ),
                ),
            ),
        ) {
            CardAccessibilityLiveRegion(state)
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
                        accent = sourceAccent,
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
                                tint = sourceAccent.copy(alpha = 0.82f),
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
                        onSetUpdateCadence = onSetUpdateCadence,
                        onSaveApk = onSaveApk,
                        onUninstall = onUninstall,
                        onManualInstall = onManualInstall,
                        onBrowseHistory = {
                            historyVisible = true
                            onBrowseHistory()
                        },
                        onChooseSource = { sourceSelectionVisible = true },
                        onChooseChannel = { channelSelectionVisible = true },
                        onOpenLanguageSettings = onOpenLanguageSettings,
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

                if (antiFeatureBadges.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        antiFeatureBadges.forEach { badge ->
                            AntiFeaturePill(badge)
                        }
                    }
                }

                if (state.historicalSelection) {
                    Text(
                        text = stringResource(R.string.historical_release_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Peach,
                    )
                }

                Text(
                    text = state.info.description
                        ?: stringResource(R.string.no_release_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                state.unmanagedInstall?.let { unmanaged ->
                    val signer = unmanaged.installedSignerSha256
                        ?: stringResource(R.string.unknown)
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(
                                R.string.installed_elsewhere_version,
                                unmanaged.installedVersionName ?: stringResource(R.string.unknown),
                                unmanaged.installedVersionCode,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Peach,
                        )
                        Text(
                            text = stringResource(
                                R.string.installed_elsewhere_package,
                                unmanaged.applicationId,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Subtext,
                        )
                        Text(
                            text = stringResource(R.string.installed_elsewhere_signer, signer),
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Subtext,
                        )
                        Text(
                            text = stringResource(R.string.installed_elsewhere_source, unmanaged.source),
                            style = MaterialTheme.typography.bodySmall,
                            color = Catppuccin.Subtext,
                        )
                    }
                }

                if (state.info.assetChoices.size > 1) {
                    Text(
                        text = stringResource(R.string.multiple_apks_choice),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Peach,
                    )
                }

                if (normalizeSha256Digest(state.info.asset.digest) == null) {
                    Text(
                        text = stringResource(R.string.integrity_unavailable),
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
                        text = stringResource(
                            R.string.installed_release_versions,
                            state.installedVersion,
                            state.info.versionName ?: state.info.tagName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Sapphire,
                    )
                } else if (
                    state.installedVersion != null &&
                    state.status == CardStatus.ReleaseAvailable
                ) {
                    Text(
                        text = stringResource(
                            R.string.installed_tag_not_inspected,
                            state.installedVersion,
                            state.info.tagName,
                        ),
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
                        color = sourceAccent,
                        trackColor = Catppuccin.Surface2,
                    )
                }

                state.message?.let { message ->
                    val messageColor = when (state.status) {
                        CardStatus.Error, CardStatus.SignatureMismatch -> Catppuccin.Red
                        CardStatus.Unmanaged -> Catppuccin.Peach
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
                        Text(
                            stringResource(
                                if (notesExpanded) {
                                    R.string.hide_release_notes
                                } else {
                                    R.string.whats_new
                                },
                            ),
                        )
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
                        if (state.info.isStale) {
                            Text(
                                text = stringResource(R.string.stale_catalog_release),
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Peach,
                                maxLines = 1,
                            )
                        } else if (isReleaseStale) {
                            Text(
                                text = stringResource(R.string.no_release_past_year),
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Peach,
                                maxLines = 1,
                            )
                        } else if (state.isIgnored) {
                            Text(
                                text = stringResource(R.string.update_alerts_muted),
                                style = MaterialTheme.typography.bodySmall,
                                color = Catppuccin.Overlay,
                                maxLines = 1,
                            )
                        }
                        SourceVerificationBadge(
                            status = state.sourceVerification,
                            onClick = { sourceVerificationVisible = true },
                        )
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
                        onRequestAdoption = { adoptionVisible = true },
                        assetSelectionRequired = state.info.assetChoices.size > 1,
                        onChooseAsset = { assetSelectionVisible = true },
                        resumeAvailable = state.resumableDownloadBytes > 0L,
                        sourceAccent = sourceAccent,
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
    accent: Color,
) {
    val palette = remember(seed, accent) {
        Triple(accent, accent.copy(alpha = 0.52f), accent)
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
private fun AntiFeaturePill(badge: AntiFeatureBadge) {
    val (accent, severityRes) = when (badge.severity) {
        AntiFeatureSeverity.Warning -> Catppuccin.Yellow to R.string.anti_feature_warning
        AntiFeatureSeverity.Danger -> Catppuccin.Red to R.string.anti_feature_danger
    }
    val severity = stringResource(severityRes)
    val accessibilityLabel = stringResource(
        R.string.anti_feature_badge_description,
        severity,
        badge.label,
    )

    Surface(
        modifier = Modifier.semantics { contentDescription = accessibilityLabel },
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f)),
    ) {
        Text(
            text = badge.label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
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
    onRequestAdoption: () -> Unit,
    assetSelectionRequired: Boolean,
    onChooseAsset: () -> Unit,
    resumeAvailable: Boolean,
    sourceAccent: Color,
) {
    val modifier = Modifier
        .widthIn(min = 122.dp)
        .height(48.dp)

    val canResume = resumeAvailable && when (status) {
        CardStatus.NotInstalled,
        CardStatus.Installed,
        CardStatus.ReleaseAvailable,
        CardStatus.UpdateAvailable,
        CardStatus.ReinstallAvailable,
        CardStatus.DowngradeAvailable,
        CardStatus.Error -> true
        CardStatus.Unmanaged,
        CardStatus.Working,
        CardStatus.SignatureMismatch,
        CardStatus.PermissionReview -> false
    }

    if (canResume) {
        AccentButton(
            text = stringResource(R.string.resume_download),
            icon = Icons.Default.Download,
            accent = Catppuccin.Peach,
            onClick = onInstall,
            modifier = modifier,
        )
        return
    }

    if (assetSelectionRequired) {
        AccentButton(
            text = stringResource(R.string.choose_apk),
            icon = Icons.Default.Warning,
            accent = Catppuccin.Peach,
            onClick = onChooseAsset,
            modifier = modifier,
        )
        return
    }

    when (status) {
        CardStatus.NotInstalled -> {
            AccentButton(
                text = stringResource(R.string.install),
                icon = Icons.Default.Download,
                accent = sourceAccent,
                onClick = onInstall,
                modifier = modifier,
            )
        }
        CardStatus.Unmanaged -> {
            AccentButton(
                text = stringResource(R.string.adopt_installed_app),
                icon = Icons.Default.Security,
                accent = Catppuccin.Peach,
                onClick = onRequestAdoption,
                modifier = modifier,
            )
        }
        CardStatus.ReleaseAvailable -> {
            AccentButton(
                text = stringResource(R.string.check_release),
                icon = Icons.Default.Refresh,
                accent = Catppuccin.Sapphire,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.UpdateAvailable -> {
            AccentButton(
                text = stringResource(R.string.update),
                icon = Icons.Default.SystemUpdateAlt,
                accent = sourceAccent,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.ReinstallAvailable -> {
            AccentButton(
                text = stringResource(R.string.reinstall),
                icon = Icons.Default.Download,
                accent = Catppuccin.Sapphire,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.DowngradeAvailable -> {
            AccentButton(
                text = stringResource(R.string.downgrade),
                icon = Icons.Default.Warning,
                accent = Catppuccin.Peach,
                onClick = onUpdate,
                modifier = modifier,
            )
        }
        CardStatus.Installed -> {
            AccentButton(
                text = stringResource(R.string.open),
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
                Text(stringResource(R.string.cancel))
            }
        }
        CardStatus.Error -> {
            AccentButton(
                text = stringResource(R.string.retry),
                icon = Icons.Default.Refresh,
                accent = sourceAccent,
                onClick = onInstall,
                modifier = modifier,
            )
        }
        CardStatus.SignatureMismatch -> {
            if (onReviewTrust != null) {
                AccentButton(
                    text = stringResource(R.string.review_trust),
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
                    Text(stringResource(R.string.blocked))
                }
            }
        }
        CardStatus.PermissionReview -> {
            AccentButton(
                text = stringResource(R.string.install),
                icon = Icons.Default.Security,
                accent = Catppuccin.Peach,
                onClick = onProceedPermissions,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun formatAssetSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> stringResource(
        R.string.asset_size_mib,
        bytes / (1024f * 1024f),
    )
    bytes >= 1024L -> stringResource(R.string.asset_size_kib, bytes / 1024f)
    else -> stringResource(R.string.asset_size_bytes, bytes)
}

private fun formatHistoricalDate(value: String?): String {
    if (value.isNullOrBlank()) return "—"
    return runCatching {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date.from(Instant.parse(value)))
    }.getOrElse { value }
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
    onSetUpdateCadence: (UpdateCadence) -> Unit,
    onSaveApk: () -> Unit,
    onUninstall: () -> Unit,
    onManualInstall: () -> Unit,
    onBrowseHistory: () -> Unit,
    onChooseSource: () -> Unit,
    onChooseChannel: () -> Unit,
    onOpenLanguageSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var cadenceDialogVisible by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(
                    R.string.more_actions_for,
                    state.info.displayName,
                ),
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
                    text = stringResource(R.string.open_app),
                    icon = Icons.Default.PlayArrow,
                    onClick = {
                        expanded = false
                        onOpen()
                    },
                )
            }
            if (!state.historicalSelection &&
                state.status == CardStatus.UpdateAvailable &&
                state.queuedUpdateStatus?.isPending != true
            ) {
                ReleaseMenuItem(
                    text = stringResource(R.string.queue_update),
                    icon = Icons.Default.Schedule,
                    onClick = {
                        expanded = false
                        onQueueUpdate()
                    },
                )
            }
            if (state.queuedUpdateStatus?.isPending == true) {
                ReleaseMenuItem(
                    text = stringResource(R.string.cancel_queued_update),
                    icon = Icons.Default.Close,
                    onClick = {
                        expanded = false
                        onCancelQueuedUpdate()
                    },
                    tint = Catppuccin.Red,
                )
            }
            if (state.status.hasManagedInstall()) {
                ReleaseMenuItem(
                    text = stringResource(
                        if (state.isIgnored) {
                            R.string.unmute_update_alerts
                        } else {
                            R.string.mute_update
                        },
                    ),
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
                ReleaseMenuItem(
                    text = stringResource(R.string.update_cadence_menu),
                    icon = Icons.Default.Refresh,
                    onClick = {
                        expanded = false
                        cadenceDialogVisible = true
                    },
                )
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                state.status.hasInstalledApp() &&
                !state.info.applicationId.isNullOrBlank()
            ) {
                ReleaseMenuItem(
                    text = stringResource(R.string.app_language_settings),
                    icon = Icons.Default.Info,
                    onClick = {
                        expanded = false
                        onOpenLanguageSettings()
                    },
                )
            }
            if (state.status == CardStatus.PermissionReview) {
                ReleaseMenuItem(
                    text = stringResource(R.string.cancel_permission_review),
                    icon = Icons.Default.Close,
                    onClick = {
                        expanded = false
                        onCancelPermissions()
                    },
                )
            }
            if (state.status == CardStatus.Unmanaged) {
                ReleaseMenuItem(
                    text = stringResource(R.string.manual_install_release),
                    icon = Icons.Default.Download,
                    onClick = {
                        expanded = false
                        onManualInstall()
                    },
                    tint = Catppuccin.Peach,
                )
            }
            if (state.status != CardStatus.Working) {
                ReleaseMenuItem(
                    text = stringResource(R.string.save_apk),
                    icon = Icons.Default.SaveAlt,
                    onClick = {
                        expanded = false
                        onSaveApk()
                    },
                )
            }
            if (state.alternativeSources.isNotEmpty()) {
                ReleaseMenuItem(
                    text = stringResource(R.string.choose_preferred_source),
                    icon = Icons.Default.Security,
                    onClick = {
                        expanded = false
                        onChooseSource()
                    },
                )
            }
            ReleaseMenuItem(
                text = state.channelPreference?.let {
                    stringResource(
                        R.string.release_channel_pinned_menu,
                        releaseChannelName(it),
                    )
                } ?: stringResource(R.string.release_channel_set_menu),
                icon = Icons.Default.Schedule,
                onClick = {
                    expanded = false
                    onChooseChannel()
                },
            )
            ReleaseMenuItem(
                text = stringResource(R.string.release_history),
                icon = Icons.Default.Schedule,
                onClick = {
                    expanded = false
                    onBrowseHistory()
                },
            )
            ReleaseMenuItem(
                text = stringResource(R.string.open_repository),
                icon = Icons.Default.Code,
                onClick = {
                    expanded = false
                    onRepo()
                },
            )
            if (state.status.hasInstalledApp()) {
                ReleaseMenuItem(
                    text = stringResource(R.string.app_details_uninstall),
                    icon = Icons.Default.Info,
                    onClick = {
                        expanded = false
                        onUninstall()
                    },
                    tint = Catppuccin.Red,
                )
            }
        }
        if (cadenceDialogVisible) {
            UpdateCadenceDialog(
                state = state,
                onDismiss = { cadenceDialogVisible = false },
                onSelect = { cadence ->
                    onSetUpdateCadence(cadence)
                    cadenceDialogVisible = false
                },
            )
        }
    }
}

@Composable
private fun UpdateCadenceDialog(
    state: CardState,
    onDismiss: () -> Unit,
    onSelect: (UpdateCadence) -> Unit,
) {
    val heldUntil = state.updateCadence.heldUntilEpochMillis
        ?.takeIf { it > System.currentTimeMillis() }
    val heldLabel = heldUntil?.let {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.update_cadence_title, state.info.displayName))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.update_cadence_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                CadenceChoice(
                    label = stringResource(R.string.update_cadence_auto),
                    selected = state.updateCadence.mode == UpdateCadenceMode.Auto && heldUntil == null,
                    onClick = { onSelect(UpdateCadence(UpdateCadenceMode.Auto)) },
                )
                CadenceChoice(
                    label = stringResource(R.string.update_cadence_notify),
                    selected = state.updateCadence.mode == UpdateCadenceMode.Notify,
                    onClick = { onSelect(UpdateCadence(UpdateCadenceMode.Notify)) },
                )
                CadenceChoice(
                    label = stringResource(R.string.update_cadence_pinned),
                    selected = state.updateCadence.mode == UpdateCadenceMode.Pinned,
                    onClick = { onSelect(UpdateCadence(UpdateCadenceMode.Pinned)) },
                )
                if (heldLabel != null) {
                    Text(
                        text = stringResource(R.string.update_cadence_held_until, heldLabel),
                        style = MaterialTheme.typography.bodySmall,
                        color = Catppuccin.Peach,
                    )
                }
                CadenceChoice(
                    label = stringResource(R.string.update_cadence_hold_one_day),
                    selected = false,
                    onClick = {
                        onSelect(
                            UpdateCadence(
                                mode = UpdateCadenceMode.Auto,
                                heldUntilEpochMillis = System.currentTimeMillis() + DAY_MILLIS,
                            ),
                        )
                    },
                )
                CadenceChoice(
                    label = stringResource(R.string.update_cadence_hold_one_week),
                    selected = false,
                    onClick = {
                        onSelect(
                            UpdateCadence(
                                mode = UpdateCadenceMode.Auto,
                                heldUntilEpochMillis = System.currentTimeMillis() + WEEK_MILLIS,
                            ),
                        )
                    },
                )
                if (heldUntil != null) {
                    CadenceChoice(
                        label = stringResource(R.string.update_cadence_resume),
                        selected = false,
                        onClick = { onSelect(UpdateCadence(UpdateCadenceMode.Auto)) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun CadenceChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
    ) {
        Text(
            text = if (selected) "✓ $label" else label,
            color = if (selected) Catppuccin.MauveStrong else Catppuccin.TextStrong,
        )
    }
}

private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
private const val WEEK_MILLIS = 7L * DAY_MILLIS

private fun CardStatus.hasInstalledApp(): Boolean = this in setOf(
    CardStatus.Unmanaged,
    CardStatus.Installed,
    CardStatus.ReleaseAvailable,
    CardStatus.UpdateAvailable,
    CardStatus.ReinstallAvailable,
    CardStatus.DowngradeAvailable,
)

private fun sourceDisplayName(info: com.sysadmin.lasstore.domain.AppInfo): String =
    if (info.sourceLabel == info.owner) info.handle else "${info.sourceLabel} · ${info.handle}"

@Composable
private fun releaseChannelName(channel: ReleaseChannel): String = when (channel) {
    ReleaseChannel.STABLE -> stringResource(R.string.release_channel_stable)
    ReleaseChannel.BETA -> stringResource(R.string.release_channel_beta)
    ReleaseChannel.ALPHA -> stringResource(R.string.release_channel_alpha)
    ReleaseChannel.NIGHTLY -> stringResource(R.string.release_channel_nightly)
    ReleaseChannel.RC -> stringResource(R.string.release_channel_rc)
    ReleaseChannel.DEV -> stringResource(R.string.release_channel_dev)
    ReleaseChannel.PREVIEW -> stringResource(R.string.release_channel_preview)
}

@Composable
private fun channelOptionText(label: String, selected: Boolean) {
    Text(
        text = if (selected) {
            stringResource(R.string.release_channel_selected, label)
        } else {
            label
        },
        modifier = Modifier.fillMaxWidth(),
        color = if (selected) Catppuccin.Mint else Catppuccin.TextStrong,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
    )
}

private fun CardStatus.hasManagedInstall(): Boolean =
    hasInstalledApp() && this != CardStatus.Unmanaged

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
                    text = stringResource(R.string.new_dangerous_permissions),
                    style = MaterialTheme.typography.titleSmall,
                    color = Catppuccin.Peach,
                )
            }
            permissions.forEach { permission ->
                Text(
                    text = stringResource(
                        R.string.permission_item,
                        permission.removePrefix("android.permission."),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
            }
            TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(stringResource(R.string.cancel_review), color = Catppuccin.Red)
            }
        }
    }
}

@Composable
private fun SourceVerificationBadge(
    status: SourceVerificationStatus,
    onClick: () -> Unit,
) {
    val label = sourceVerificationLabel(status)
    val description = stringResource(
        R.string.source_verification_badge_description,
        label,
    )
    val color = when (status) {
        SourceVerificationStatus.Verified -> Catppuccin.Mint
        SourceVerificationStatus.Unverified -> Catppuccin.Peach
        SourceVerificationStatus.Unknown -> Catppuccin.Sapphire
    }
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = description
            },
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Security,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

@Composable
private fun sourceVerificationLabel(status: SourceVerificationStatus): String = stringResource(
    when (status) {
        SourceVerificationStatus.Verified -> R.string.source_verification_verified
        SourceVerificationStatus.Unverified -> R.string.source_verification_unverified
        SourceVerificationStatus.Unknown -> R.string.source_verification_unknown
    },
)

@Composable
private fun SourceVerificationDialog(
    sourceLabel: String,
    status: SourceVerificationStatus,
    onDismiss: () -> Unit,
    onOpenAdvancedSideloading: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val label = sourceVerificationLabel(status)
    val body = when (status) {
        SourceVerificationStatus.Verified -> stringResource(
            R.string.source_verification_verified_body,
            sourceLabel,
        )
        SourceVerificationStatus.Unverified -> stringResource(
            R.string.source_verification_unverified_body,
            sourceLabel,
        )
        SourceVerificationStatus.Unknown -> stringResource(
            R.string.source_verification_unknown_body,
            sourceLabel,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.source_verification_title, label)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body)
                Text(
                    text = stringResource(R.string.source_verification_google_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                )
                TextButton(
                    onClick = {
                        uriHandler.openUri(DeveloperVerificationCopy.OFFICIAL_GUIDANCE_URL)
                    },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Text(stringResource(R.string.official_android_guidance))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenAdvancedSideloading) {
                Text(stringResource(R.string.open_advanced_sideloading))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
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
                    Text(stringResource(R.string.official_android_guidance))
                }
            }
        }
    }
}
