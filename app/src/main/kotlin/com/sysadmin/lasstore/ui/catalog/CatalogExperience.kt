package com.sysadmin.lasstore.ui.catalog

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect
import com.sysadmin.lasstore.R
import com.sysadmin.lasstore.domain.CardStatus
import com.sysadmin.lasstore.ui.theme.Catppuccin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun CatalogExperience(
    viewModel: CatalogViewModel = viewModel(),
    activityResumed: Flow<Unit> = emptyFlow(),
    onOpenSettings: () -> Unit = {},
    onBeforeQueue: (CardState, (Boolean) -> Unit) -> Unit = { _, continueQueue ->
        continueQueue(true)
    },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel, activityResumed) {
        activityResumed.collect { viewModel.onActivityResumed() }
    }
    val visibleCards = remember(state.cards, state.searchQuery) {
        filterCards(state.cards, state.searchQuery)
    }
    val updateCount = remember(state.cards) {
        state.cards.count { it.status == CardStatus.UpdateAvailable }
    }
    val sourceCount = remember(state.cards) {
        state.cards.map { it.info.sourceKey }.distinct().size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Catppuccin.Crust),
    ) {
        CatalogAccessibilityLiveRegion(state)
        CatalogHero(
            refreshing = state.refreshing,
            onRefresh = {
                viewModel.refreshInstallPermission()
                viewModel.refresh()
            },
        )

        CatalogMetrics(
            appCount = state.cards.size,
            updateCount = updateCount,
            sourceCount = sourceCount,
        )

        if (!state.canRequestInstalls) {
            PermissionStrip(onClick = viewModel::openInstallPermissionSettings)
        }

        state.warning?.let { warning ->
            WarningStrip(text = warning, onDismiss = viewModel::dismissWarning)
        }
        state.catalogNotice?.let { notice ->
            CatalogNoticeStrip(text = notice)
        }

        CatalogSearchSurface(
            query = state.searchQuery,
            totalCount = state.cards.size,
            visibleCount = visibleCards.size,
            onQueryChange = viewModel::updateSearchQuery,
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            when {
                state.refreshing && state.cards.isEmpty() -> CatalogLoading()
                state.cards.isEmpty() -> {
                    CatalogEmpty(
                        noEnabledSources = state.noEnabledSources,
                        errorMessage = state.errorMessage,
                        onOpenSettings = onOpenSettings,
                        onRefresh = {
                            viewModel.refreshInstallPermission()
                            viewModel.refresh()
                        },
                    )
                }
                visibleCards.isEmpty() -> {
                    SearchEmpty(
                        query = state.searchQuery,
                        onClear = { viewModel.updateSearchQuery("") },
                    )
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 332.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            top = 6.dp,
                            end = 16.dp,
                            bottom = 20.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            items = visibleCards,
                            key = { "${it.info.sourceKey}/${it.info.owner}/${it.info.repo}" },
                        ) { card ->
                            ReleaseCard(
                                state = card,
                                onInstall = { viewModel.install(card) },
                                onUpdate = { viewModel.install(card) },
                                onQueueUpdate = {
                                    onBeforeQueue(card) { notificationsGranted ->
                                        viewModel.queueBackgroundUpdate(card, notificationsGranted)
                                    }
                                },
                                onCancelQueuedUpdate = { viewModel.cancelBackgroundUpdate(card) },
                                onUninstall = { viewModel.uninstall(card) },
                                onOpen = { viewModel.open(card) },
                                onRepo = { viewModel.openRepo(card) },
                                onCancel = { viewModel.cancelInstall(card) },
                                onProceedPermissions = {
                                    viewModel.proceedFromPermissionReview(card)
                                },
                                onCancelPermissions = { viewModel.cancelPermissionReview(card) },
                                onIgnore = { viewModel.toggleIgnore(card) },
                                onSaveApk = { viewModel.saveApk(card) },
                                onReplacePublisherPin = { typedApplicationId, independentlyVerified ->
                                    viewModel.replacePublisherPin(
                                        card = card,
                                        typedApplicationId = typedApplicationId,
                                        independentlyVerified = independentlyVerified,
                                    )
                                },
                                onSelectAsset = { asset -> viewModel.selectAsset(card, asset) },
                                onAdopt = { viewModel.adopt(card) },
                                onManualInstall = {
                                    viewModel.install(card, allowUnmanagedReplacement = true)
                                },
                                onBrowseHistory = { viewModel.loadReleaseHistory(card) },
                                onLoadMoreHistory = { viewModel.loadReleaseHistory(card, append = true) },
                                onSelectHistoricalRelease = { historical ->
                                    viewModel.selectHistoricalRelease(card, historical)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CatalogHero(
    refreshing: Boolean,
    onRefresh: () -> Unit,
) {
    val brandLocal = stringResource(R.string.brand_local)
    val brandAndroid = stringResource(R.string.brand_android)
    val brandStore = stringResource(R.string.brand_store)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 12.dp, end = 18.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = stringResource(R.string.private_releases),
                style = MaterialTheme.typography.labelSmall,
                color = Catppuccin.MauveStrong,
            )
            Icon(
                imageVector = Icons.Default.Security,
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
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = Catppuccin.TextStrong)) {
                            append(brandLocal)
                        }
                        withStyle(SpanStyle(color = Catppuccin.MauveStrong)) {
                            append(brandAndroid)
                        }
                        withStyle(SpanStyle(color = Catppuccin.TextStrong)) {
                            append(brandStore)
                        }
                    },
                    style = MaterialTheme.typography.displaySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Text(
                    text = stringResource(R.string.catalog_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Catppuccin.Subtext,
                )
            }

            Spacer(Modifier.width(10.dp))
            FilledIconButton(
                onClick = onRefresh,
                enabled = !refreshing,
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Catppuccin.Surface1,
                    contentColor = Catppuccin.MauveStrong,
                    disabledContainerColor = Catppuccin.Surface1,
                    disabledContentColor = Catppuccin.MauveStrong,
                ),
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = Catppuccin.MauveStrong,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.refresh_catalog),
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogMetrics(
    appCount: Int,
    updateCount: Int,
    sourceCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MetricTile(
            icon = Icons.Default.Apps,
            value = appCount.toString(),
            label = pluralStringResource(R.plurals.catalog_app_count, appCount),
            accent = Catppuccin.MauveStrong,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            icon = Icons.Default.SystemUpdateAlt,
            value = updateCount.toString(),
            label = pluralStringResource(R.plurals.catalog_update_count, updateCount),
            accent = Catppuccin.Sapphire,
            modifier = Modifier.weight(1f),
        )
        MetricTile(
            icon = Icons.Default.CloudDone,
            value = sourceCount.toString(),
            label = pluralStringResource(R.plurals.catalog_source_count, sourceCount),
            accent = Catppuccin.Mint,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetricTile(
    icon: ImageVector,
    value: String,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.heightIn(min = 68.dp),
        shape = RoundedCornerShape(18.dp),
        color = Catppuccin.PanelRaised,
        border = BorderStroke(1.dp, Catppuccin.Stroke),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.13f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent,
                    maxLines = 1,
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
private fun PermissionStrip(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(18.dp),
        color = Catppuccin.Panel,
        border = BorderStroke(1.dp, Catppuccin.Peach.copy(alpha = 0.38f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(Catppuccin.Peach.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = Catppuccin.Peach,
                    modifier = Modifier.size(21.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.install_permission_required),
                    style = MaterialTheme.typography.titleSmall,
                    color = Catppuccin.TextStrong,
                )
                Text(
                    text = stringResource(R.string.install_permission_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = Catppuccin.Subtext,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onClick,
                border = BorderStroke(1.dp, Catppuccin.Mauve.copy(alpha = 0.55f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Catppuccin.MauveStrong),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.grant))
            }
        }
    }
}

@Composable
private fun WarningStrip(
    text: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.Red.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Catppuccin.Red.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 7.dp, end = 4.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Red,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.dismiss_message),
                    tint = Catppuccin.Red,
                )
            }
        }
    }
}

@Composable
private fun CatalogSearchSurface(
    query: String,
    totalCount: Int,
    visibleCount: Int,
    onQueryChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.search_apps_repos_versions)) },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            trailingIcon = {
                if (query.isNotBlank()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.clear_search),
                        )
                    }
                }
            },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Catppuccin.TextStrong,
                unfocusedTextColor = Catppuccin.TextStrong,
                focusedContainerColor = Catppuccin.PanelRaised,
                unfocusedContainerColor = Catppuccin.PanelRaised,
                focusedBorderColor = Catppuccin.Mauve,
                unfocusedBorderColor = Catppuccin.StrokeBright,
                focusedLeadingIconColor = Catppuccin.MauveStrong,
                unfocusedLeadingIconColor = Catppuccin.Subtext,
                focusedTrailingIconColor = Catppuccin.MauveStrong,
                unfocusedTrailingIconColor = Catppuccin.Subtext,
                cursorColor = Catppuccin.MauveStrong,
                focusedPlaceholderColor = Catppuccin.Subtext,
                unfocusedPlaceholderColor = Catppuccin.Subtext,
            ),
        )
        if (query.isNotBlank() && totalCount > 0) {
            Text(
                text = stringResource(R.string.catalog_release_count, visibleCount, totalCount),
                style = MaterialTheme.typography.labelSmall,
                color = Catppuccin.Subtext,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
private fun CatalogLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CircularProgressIndicator(color = Catppuccin.MauveStrong, strokeWidth = 3.dp)
            Text(
                text = stringResource(R.string.checking_private_releases),
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
            )
        }
    }
}

@Composable
private fun CatalogEmpty(
    noEnabledSources: Boolean,
    errorMessage: String?,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(Catppuccin.Surface1, RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        noEnabledSources -> Icons.Default.Settings
                        errorMessage == null -> {
                            Icons.Outlined.Inbox
                        }
                        else -> Icons.Default.Warning
                    },
                    contentDescription = null,
                    tint = Catppuccin.MauveStrong,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                text = when {
                    noEnabledSources -> stringResource(R.string.no_enabled_sources)
                    errorMessage == null -> stringResource(R.string.no_releases_on_shelf)
                    else -> stringResource(R.string.catalog_unavailable)
                },
                style = MaterialTheme.typography.titleLarge,
                color = Catppuccin.TextStrong,
            )
            Text(
                text = when {
                    noEnabledSources -> stringResource(R.string.no_enabled_sources_body)
                    errorMessage != null -> errorMessage
                    else -> stringResource(R.string.catalog_default_empty_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (noEnabledSources) {
                Button(
                    onClick = onOpenSettings,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Catppuccin.MauveStrong,
                        contentColor = Catppuccin.Crust,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.open_source_settings))
                }
                OutlinedButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.refresh_catalog))
                }
            } else {
                Button(
                    onClick = onRefresh,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Catppuccin.MauveStrong,
                        contentColor = Catppuccin.Crust,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.refresh_catalog))
                }
            }
        }
    }
}

@Composable
private fun CatalogNoticeStrip(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
        shape = RoundedCornerShape(16.dp),
        color = Catppuccin.Yellow.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Catppuccin.Yellow.copy(alpha = 0.3f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = Catppuccin.Yellow,
                modifier = Modifier.size(19.dp),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = Catppuccin.Yellow,
            )
        }
    }
}

@Composable
private fun SearchEmpty(
    query: String,
    onClear: () -> Unit,
) {
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
            Icon(
                imageVector = Icons.Default.Inventory2,
                contentDescription = null,
                tint = Catppuccin.Sapphire,
                modifier = Modifier.size(42.dp),
            )
            Text(
                text = stringResource(R.string.nothing_matches_query, query.trim()),
                style = MaterialTheme.typography.titleMedium,
                color = Catppuccin.TextStrong,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.search_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = Catppuccin.Subtext,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            OutlinedButton(onClick = onClear) {
                Text(stringResource(R.string.clear_search))
            }
        }
    }
}
